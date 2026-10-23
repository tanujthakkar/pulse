package app.pulse.link

import app.pulse.ble.StrapGatt
import app.pulse.data.DeviceDirectory
import app.pulse.data.DeviceRow
import app.pulse.wire.codec.AccumulatorEvent
import app.pulse.wire.codec.BatteryCommand
import app.pulse.wire.codec.CommandFrames
import app.pulse.wire.codec.CommandResult
import app.pulse.wire.codec.ConnectionId
import app.pulse.wire.codec.DeniedCommands
import app.pulse.wire.codec.FrameAccumulator
import app.pulse.wire.codec.FrameCodec
import app.pulse.wire.codec.HelloBody
import app.pulse.wire.codec.HelloCommand
import app.pulse.wire.codec.ReplyMatch
import app.pulse.wire.codec.RequestTracker
import app.pulse.wire.codec.toCommandResponse
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * After the proprietary notify descriptors are on: write hello, then command 26.
 *
 * Replies are reassembled and matched with [RequestTracker]. Hello identity
 * blocks are not logged. A failed hello does not close the GATT session; the
 * link decides whether heart rate keeps the screen in a degraded state.
 * History acknowledgements are not sent from here.
 */
internal class StrapHandshake(
    private val address: String,
    private val knows: (UUID) -> Boolean,
    private val write: (ByteArray) -> Unit,
    private val read: (UUID) -> Unit,
    private val clock: LinkClock,
    private val devices: DeviceDirectory,
    private val heartRateLive: () -> Boolean,
    private val report: (DeviceReport) -> Unit,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    private val runner = HandshakeRunner()
    private val tracker = RequestTracker()
    private val connection = ConnectionId(NEXT_CONNECTION.incrementAndGet())
    private val incoming = StrapGatt.NOTIFY.associateWith { FrameAccumulator() }
    private var sequence = HelloCommand.DOCUMENTED_SEQUENCE
    private var helloSequence = HelloCommand.DOCUMENTED_SEQUENCE
    private var batterySequence: Int? = null
    private var helloWritePending = false
    private var helloDone = false
    private var deadlineExtended = false
    private var alarm: LinkAlarm? = null
    private var started = false
    private var helloFirmware: String? = null
    private var informationFirmware: String? = null
    private var commandBatteryPercent: Long? = null
    private var serviceBatteryPercent: Int? = null

    fun start() = runner.execute {
        if (started) return@execute
        started = true
        sendHello()
        if (knows(StrapGatt.FIRMWARE_REVISION)) read(StrapGatt.FIRMWARE_REVISION)
        if (knows(StrapGatt.BATTERY_LEVEL)) read(StrapGatt.BATTERY_LEVEL)
    }

    fun onNotification(characteristic: UUID, value: ByteArray) {
        val copy = value.copyOf()
        runner.execute { accept(characteristic, copy) }
    }

    fun onWriteFinished(characteristic: UUID, status: Int) = runner.execute {
        if (helloDone || characteristic != StrapGatt.COMMAND || !helloWritePending) return@execute
        helloWritePending = false
        if (status != 0) onHelloFailed()
    }

    fun onRead(characteristic: UUID, status: Int, value: ByteArray) = runner.execute {
        if (status != 0) return@execute
        when (characteristic) {
            StrapGatt.FIRMWARE_REVISION -> {
                informationFirmware = firmwareRevisionText(value)
                log("firmware revision characteristic present=${informationFirmware != null}")
                publish(settled = false, succeeded = false)
            }
            StrapGatt.BATTERY_LEVEL -> {
                serviceBatteryPercent = batteryServicePercent(value)
                log("battery service percent=${serviceBatteryPercent ?: "absent"}")
                publish(settled = false, succeeded = false)
            }
        }
    }

    fun stop() = runner.execute {
        alarm?.cancel()
        alarm = null
        helloDone = true
        helloWritePending = false
        incoming.values.forEach { it.reset() }
        tracker.dropConnection(connection)
    }

    private fun sendHello() {
        if (!DeniedCommands.allows(HelloCommand.NUMBER)) {
            onHelloFailed()
            return
        }
        val seq = sequence
        sequence = (sequence + 1) and 0xFF
        helloSequence = seq
        val bytes = HelloCommand.bytes(seq)
        tracker.open(connection, HelloCommand.NUMBER, seq)
        helloWritePending = true
        armDeadline()
        log("hello write sequence=$seq bytes=${bytes.size}")
        write(bytes)
    }

    private fun sendBattery() {
        if (!DeniedCommands.allows(BatteryCommand.NUMBER)) {
            log("battery command refused")
            return
        }
        val seq = sequence
        sequence = (sequence + 1) and 0xFF
        batterySequence = seq
        val bytes = CommandFrames.request(BatteryCommand.NUMBER, seq, BatteryCommand.REQUEST_BODY)
        tracker.open(connection, BatteryCommand.NUMBER, seq)
        log("battery write sequence=$seq bytes=${bytes.size}")
        write(bytes)
    }

    private fun accept(characteristic: UUID, value: ByteArray) {
        val accumulator = incoming[characteristic] ?: return
        for (event in accumulator.append(value)) {
            if (event is AccumulatorEvent.Frame) onFrame(event.frame)
        }
    }

    private fun onFrame(frame: app.pulse.wire.codec.WireFrame) {
        val response = frame.toCommandResponse() ?: return
        when (val match = tracker.onFrame(connection, frame)) {
            is ReplyMatch.Matched -> onMatch(match, response.body)
            is ReplyMatch.Unmatched -> Unit
        }
    }

    private fun onMatch(match: ReplyMatch.Matched, body: ByteArray) {
        when {
            match.command == HelloCommand.NUMBER && match.originSequence == helloSequence -> onHello(match, body)
            match.command == BatteryCommand.NUMBER && match.originSequence == batterySequence -> onBattery(match, body)
        }
    }

    private fun onHello(match: ReplyMatch.Matched, body: ByteArray) {
        when {
            match.pending -> {
                log(HelloBody.summary(body))
                if (!deadlineExtended) {
                    deadlineExtended = true
                    armDeadline()
                }
            }
            match.result == CommandResult.SUCCESS -> onHelloSuccess(body)
            else -> onHelloFailed()
        }
    }

    private fun onHelloSuccess(body: ByteArray) {
        if (helloDone) return
        helloDone = true
        helloWritePending = false
        alarm?.cancel()
        alarm = null
        helloFirmware = HelloBody.parse(body)?.versionText
        log(HelloBody.summary(body))
        publish(settled = true, succeeded = true)
        sendBattery()
    }

    private fun onHelloFailed() {
        if (helloDone) return
        helloDone = true
        helloWritePending = false
        alarm?.cancel()
        alarm = null
        tracker.dropConnection(connection)
        log("hello failed identity=redacted")
        publish(settled = true, succeeded = false)
    }

    private fun onBattery(match: ReplyMatch.Matched, body: ByteArray) {
        if (match.pending) return
        if (match.result != CommandResult.SUCCESS) {
            log("battery result=${match.result}")
            return
        }
        commandBatteryPercent = BatteryCommand.percent(body)
        log("battery command percent=${commandBatteryPercent ?: "absent"}")
        publish(settled = false, succeeded = false)
    }

    private fun publish(settled: Boolean, succeeded: Boolean) {
        devices.save(
            DeviceRow(
                address = address,
                helloFirmware = helloFirmware,
                informationFirmware = informationFirmware,
                commandBatteryPercent = commandBatteryPercent,
                serviceBatteryPercent = serviceBatteryPercent,
                updatedAtMillis = nowMillis(),
            ),
        )
        report(
            DeviceReport(
                helloFirmware = helloFirmware,
                informationFirmware = informationFirmware,
                commandBatteryPercent = commandBatteryPercent,
                serviceBatteryPercent = serviceBatteryPercent,
                settled = settled,
                succeeded = succeeded,
                heartRateLive = heartRateLive(),
            ),
        )
    }

    private fun armDeadline() {
        alarm?.cancel()
        alarm = clock.after(HELLO_WAIT_MS) {
            runner.execute {
                if (!helloDone) onHelloFailed()
            }
        }
    }

    companion object {
        const val HELLO_WAIT_MS: Long = 10_000
        private val NEXT_CONNECTION = AtomicLong(0)
    }
}

private class HandshakeRunner {
    private val queue = ArrayDeque<() -> Unit>()
    private var draining = false

    fun execute(task: () -> Unit) {
        queue.add(task)
        if (draining) return
        draining = true
        try {
            while (queue.isNotEmpty()) {
                queue.removeFirst().invoke()
            }
        } finally {
            draining = false
        }
    }
}
