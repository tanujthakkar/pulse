package app.pulse.ble

import java.util.ArrayDeque
import java.util.UUID

/**
 * One strap link: connect, discover, request MTU, subscribe, then notifications.
 *
 * The platform allows a single outstanding GATT operation. This session queues
 * every operation and starts the next only after the previous one has ended.
 * [disconnect] reaches [GattRadio.close] so the platform client is not left
 * registered. A stuck operation is closed the same way when [OP_TIMEOUT_MS]
 * elapses, without starting a second operation on top of it.
 *
 * The client hello and other opcodes are not sent from here. Command writes
 * use write-with-response unless the caller says otherwise. Hardware timing
 * for scan and connect cycles is in `docs/hardware-acceptance.md`.
 */
class GattSession internal constructor(
    private val radio: GattRadio,
    private val events: SessionEvents,
    private val alarms: AlarmClock,
    private val log: OpLogger = OpLogger { },
    private val notifyOrder: List<UUID> = StrapGatt.NOTIFY,
    private val requestedMtu: Int = StrapGatt.REQUESTED_MTU,
    private val opTimeoutMs: Long = OP_TIMEOUT_MS,
) {
    private val runner = SerialRunner()
    private val queue = ArrayDeque<Op>()
    private val deferredWrites = ArrayDeque<Op.Write>()
    private val opLog = GattOpLog()
    private var inFlight: Op? = null
    private var timeoutToken = 0
    private var alarm: Alarm? = null
    private var negotiatedMtu = requestedMtu
    private var connected = false
    private var readyNotified = false

    @Volatile
    var phase: SessionPhase = SessionPhase.IDLE
        private set

    @Volatile
    var closeReason: CloseReason? = null
        private set

    init {
        radio.listener = object : RadioListener {
            override fun onConnectionState(status: Int, connected: Boolean) {
                runner.execute { handleConnection(status, connected) }
            }

            override fun onServices(status: Int, discovered: DiscoveredGatt) {
                runner.execute { handleServices(status, discovered) }
            }

            override fun onMtu(status: Int, mtu: Int) {
                runner.execute { handleMtu(status, mtu) }
            }

            override fun onDescriptorWrite(status: Int) {
                runner.execute { handleDescriptor(status) }
            }

            override fun onCharacteristicWrite(status: Int) {
                runner.execute { handleCharacteristicWrite(status) }
            }

            override fun onNotification(characteristic: UUID, value: ByteArray) {
                val copy = value.copyOf()
                runner.execute { handleNotification(characteristic, copy) }
            }
        }
    }

    fun open(address: String) {
        runner.execute { startOpen(address) }
    }

    /**
     * Queue a characteristic write. The default is write-with-response, which
     * is what command payloads require. Writes before the session is ready
     * wait until the notify descriptors are in place.
     */
    fun write(characteristic: UUID, value: ByteArray, withResponse: Boolean = true) {
        val payload = value.copyOf()
        runner.execute { enqueueWrite(Op.Write(characteristic, payload, withResponse)) }
    }

    /** Drop queued work, disconnect if the link is up, and close the client. */
    fun disconnect() {
        runner.execute { requestClose(CloseReason.REQUESTED) }
    }

    fun opLogSnapshot(): List<String> = opLog.snapshot()

    fun overlapCount(): Int = opLog.overlapCount

    private fun startOpen(address: String) {
        if (phase != SessionPhase.IDLE) return
        phase = SessionPhase.OPENING
        launch(Op.Connect(address))
    }

    private fun enqueueWrite(op: Op.Write) {
        when (phase) {
            SessionPhase.OPENING -> deferredWrites.add(op)
            SessionPhase.READY -> {
                queue.add(op)
                pump()
            }
            SessionPhase.IDLE,
            SessionPhase.CLOSING,
            SessionPhase.CLOSED,
            -> Unit
        }
    }

    private fun requestClose(reason: CloseReason) {
        if (phase == SessionPhase.CLOSED || phase == SessionPhase.CLOSING || phase == SessionPhase.IDLE) {
            return
        }
        deferredWrites.clear()
        queue.clear()
        closeReason = reason
        phase = SessionPhase.CLOSING
        if (inFlight == null) {
            launch(Op.Disconnect)
        } else {
            queue.add(Op.Disconnect)
        }
    }

    private fun launch(op: Op) {
        check(inFlight == null) { "GATT op ${op.name} started while ${inFlight?.name} was in flight" }
        inFlight = op
        opLog.begin(op.name)
        log.log("BEGIN ${op.name}")
        arm(op)
        when (op) {
            is Op.Connect -> if (!radio.connect(op.address)) fail(GattStatus.START_FAILED)
            is Op.Discover -> if (!radio.discoverServices()) fail(GattStatus.START_FAILED)
            is Op.RequestMtu -> if (!radio.requestMtu(requestedMtu)) fail(GattStatus.START_FAILED)
            is Op.SetNotify -> {
                radio.setNotify(op.characteristic, true)
                clearInFlight(GattStatus.SUCCESS)
                pump()
            }
            is Op.Cccd -> {
                val payload = StrapGatt.ENABLE_NOTIFICATION.copyOf()
                if (!radio.writeCccd(op.characteristic, payload)) fail(GattStatus.START_FAILED)
            }
            is Op.Write -> {
                if (!radio.writeCharacteristic(op.characteristic, op.value, op.withResponse)) {
                    fail(GattStatus.START_FAILED)
                }
            }
            is Op.Disconnect -> {
                if (connected) {
                    radio.disconnect()
                } else {
                    clearInFlight(GattStatus.SUCCESS)
                    closeRadio(closeReason ?: CloseReason.REQUESTED)
                }
            }
        }
    }

    private fun pump() {
        if (inFlight != null || phase == SessionPhase.CLOSED) return
        val next = queue.pollFirst() ?: return
        launch(next)
    }

    private fun handleConnection(status: Int, connectedNow: Boolean) {
        if (phase == SessionPhase.CLOSED) return
        if (connectedNow) {
            if (status != GattStatus.SUCCESS || inFlight !is Op.Connect) {
                if (inFlight is Op.Connect) clearInFlight(status.ifZero(GattStatus.LINK_DROPPED))
                closeRadio(closeReason ?: CloseReason.FAILED)
                return
            }
            connected = true
            clearInFlight(GattStatus.SUCCESS)
            if (phase == SessionPhase.CLOSING) pump() else launch(Op.Discover)
            return
        }
        connected = false
        when (inFlight) {
            is Op.Connect -> {
                clearInFlight(status.ifZero(GattStatus.LINK_DROPPED))
                queue.clear()
                closeRadio(closeReason ?: CloseReason.FAILED)
            }
            is Op.Disconnect -> {
                clearInFlight(status)
                closeRadio(closeReason ?: CloseReason.REQUESTED)
            }
            else -> {
                if (inFlight != null) clearInFlight(status.ifZero(GattStatus.LINK_DROPPED))
                queue.clear()
                deferredWrites.clear()
                closeRadio(closeReason ?: CloseReason.LINK_LOST)
            }
        }
    }

    private fun handleServices(status: Int, discovered: DiscoveredGatt) {
        if (phase == SessionPhase.CLOSED || inFlight !is Op.Discover) return
        if (status != GattStatus.SUCCESS) {
            clearInFlight(status)
            abandon()
            return
        }
        if (phase == SessionPhase.CLOSING) {
            clearInFlight(GattStatus.SUCCESS)
            pump()
            return
        }
        val missingService = StrapGatt.SERVICE !in discovered.services
        val missingNotify = notifyOrder.any { it !in discovered.characteristics }
        if (missingService || missingNotify) {
            clearInFlight(GattStatus.MISSING)
            closeReason = CloseReason.FAILED
            abandon()
            return
        }
        clearInFlight(GattStatus.SUCCESS)
        launch(Op.RequestMtu)
    }

    private fun handleMtu(status: Int, mtu: Int) {
        if (phase == SessionPhase.CLOSED || inFlight !is Op.RequestMtu) return
        if (status != GattStatus.SUCCESS) {
            clearInFlight(status)
            abandon()
            return
        }
        negotiatedMtu = mtu
        clearInFlight(GattStatus.SUCCESS)
        if (phase == SessionPhase.CLOSING) {
            pump()
            return
        }
        for (characteristic in notifyOrder) {
            queue.add(Op.SetNotify(characteristic))
            queue.add(Op.Cccd(characteristic))
        }
        pump()
    }

    private fun handleDescriptor(status: Int) {
        if (phase == SessionPhase.CLOSED) return
        val op = inFlight as? Op.Cccd ?: return
        if (status != GattStatus.SUCCESS) {
            clearInFlight(status)
            abandon()
            return
        }
        clearInFlight(GattStatus.SUCCESS)
        if (phase == SessionPhase.CLOSING) {
            pump()
            return
        }
        val subscriptionsDone = op.characteristic == notifyOrder.last()
        if (subscriptionsDone && !readyNotified) {
            readyNotified = true
            phase = SessionPhase.READY
            queue.addAll(deferredWrites)
            deferredWrites.clear()
            val mtu = negotiatedMtu
            try {
                events.onReady(mtu)
            } finally {
                if (phase == SessionPhase.READY) pump()
            }
            return
        }
        pump()
    }

    private fun handleCharacteristicWrite(status: Int) {
        if (phase == SessionPhase.CLOSED) return
        val op = inFlight as? Op.Write ?: return
        clearInFlight(status)
        if (phase == SessionPhase.READY) {
            events.onWriteFinished(op.characteristic, status)
        }
        pump()
    }

    private fun handleNotification(characteristic: UUID, value: ByteArray) {
        if (phase == SessionPhase.CLOSED || phase == SessionPhase.IDLE) return
        opLog.mark("NOTIFY $characteristic bytes=${value.size}")
        log.log("NOTIFY $characteristic bytes=${value.size}")
        events.onNotification(characteristic, value)
    }

    private fun fail(status: Int) {
        if (inFlight != null) clearInFlight(status)
        closeReason = closeReason ?: CloseReason.FAILED
        abandon()
    }

    private fun abandon() {
        if (phase == SessionPhase.CLOSED) return
        queue.clear()
        deferredWrites.clear()
        val reason = closeReason ?: CloseReason.FAILED
        closeReason = reason
        if (!connected) {
            closeRadio(reason)
            return
        }
        phase = SessionPhase.CLOSING
        if (inFlight == null) launch(Op.Disconnect) else queue.add(Op.Disconnect)
    }

    private fun onTimeout(token: Int, op: Op) {
        if (token != timeoutToken || inFlight !== op || phase == SessionPhase.CLOSED) return
        clearInFlight(GattStatus.TIMEOUT)
        queue.clear()
        deferredWrites.clear()
        // The in-flight call never came back. close() releases the client
        // without queueing another operation on top of the stuck one.
        closeRadio(CloseReason.TIMED_OUT)
    }

    private fun closeRadio(reason: CloseReason) {
        if (phase == SessionPhase.CLOSED) return
        phase = SessionPhase.CLOSED
        closeReason = reason
        connected = false
        alarm?.cancel()
        timeoutToken += 1
        inFlight = null
        queue.clear()
        deferredWrites.clear()
        try {
            radio.close()
        } finally {
            opLog.mark("CLOSE")
            log.log("CLOSE")
            alarms.close()
            events.onClosed(reason)
        }
    }

    private fun clearInFlight(status: Int) {
        val op = inFlight ?: return
        alarm?.cancel()
        timeoutToken += 1
        inFlight = null
        opLog.end(op.name, status)
        log.log("END ${op.name} status=$status")
    }

    private fun arm(op: Op) {
        alarm?.cancel()
        val token = ++timeoutToken
        alarm = alarms.after(opTimeoutMs) {
            runner.execute { onTimeout(token, op) }
        }
    }

    private sealed interface Op {
        val name: String

        data class Connect(val address: String) : Op {
            override val name: String = "connect"
        }

        data object Discover : Op {
            override val name: String = "discover"
        }

        data object RequestMtu : Op {
            override val name: String = "requestMtu"
        }

        data class SetNotify(val characteristic: UUID) : Op {
            override val name: String = "setNotify:$characteristic"
        }

        data class Cccd(val characteristic: UUID) : Op {
            override val name: String = "cccd:$characteristic"
        }

        data class Write(
            val characteristic: UUID,
            val value: ByteArray,
            val withResponse: Boolean,
        ) : Op {
            override val name: String = "write:$characteristic"
        }

        data object Disconnect : Op {
            override val name: String = "disconnect"
        }
    }

    companion object {
        const val OP_TIMEOUT_MS: Long = 10_000
    }
}

private fun Int.ifZero(replacement: Int): Int = if (this == 0) replacement else this
