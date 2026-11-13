package app.pulse.ble

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

/**
 * In-process [GattRadio] that speaks the strap frame codec.
 *
 * Completions are posted so they run after the start call returns. History
 * notifications use the data characteristic. Command replies use the response
 * characteristic. Live heart rate starts after its notify descriptor is written.
 */
internal class SimulatedGattRadio(context: Context) : GattRadio {
    override var listener: RadioListener? = null

    private val engine = SimulatedStrapEngine(SimulatedStrap.scenario(context))
    private val handler = Handler(Looper.getMainLooper())
    private val discovered = DiscoveredGatt(
        services = setOf(
            StrapGatt.SERVICE,
            StrapGatt.HEART_RATE_SERVICE,
            StrapGatt.BATTERY_SERVICE,
            StrapGatt.DEVICE_INFO_SERVICE,
        ),
        characteristics = setOf(
            StrapGatt.COMMAND,
            StrapGatt.RESPONSE_NOTIFY,
            StrapGatt.EVENT_NOTIFY,
            StrapGatt.DATA_NOTIFY,
            StrapGatt.EXTRA_NOTIFY,
            StrapGatt.HEART_RATE_MEASUREMENT,
            StrapGatt.BATTERY_LEVEL,
            StrapGatt.FIRMWARE_REVISION,
        ),
    )
    private var closed = false
    private var connected = false
    private var heartOn = false
    private var heartTick = 0
    private var pumping = false

    private val heart = object : Runnable {
        override fun run() {
            if (closed || !heartOn) return
            val value = SimulatedStrapEngine.heartRateNotification(heartTick)
            heartTick += 1
            listener?.onNotification(StrapGatt.HEART_RATE_MEASUREMENT, value)
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun connect(address: String): Boolean {
        if (closed) return false
        Log.i(BleClients.LOG_TAG, "simulated strap connect scenario=${engine.scenario.wireName}")
        val stayDown = engine.scenario == StrapScenario.DISCONNECT && DisconnectLatch.tripped
        handler.post {
            if (closed) return@post
            if (stayDown) {
                connected = false
                listener?.onConnectionState(GattStatus.LINK_DROPPED, false)
                return@post
            }
            connected = true
            listener?.onConnectionState(GattStatus.SUCCESS, true)
        }
        return true
    }

    override fun discoverServices(): Boolean {
        if (closed || !connected) return false
        handler.post {
            if (closed) return@post
            listener?.onServices(GattStatus.SUCCESS, discovered)
        }
        return true
    }

    override fun requestMtu(mtu: Int): Boolean {
        if (closed || !connected) return false
        handler.post {
            if (closed) return@post
            listener?.onMtu(GattStatus.SUCCESS, mtu)
        }
        return true
    }

    override fun setNotify(characteristic: UUID, enable: Boolean) = Unit

    override fun writeCccd(characteristic: UUID, value: ByteArray): Boolean {
        if (closed || !connected) return false
        handler.post {
            if (closed) return@post
            listener?.onDescriptorWrite(GattStatus.SUCCESS)
            val enablingHeart = characteristic == StrapGatt.HEART_RATE_MEASUREMENT &&
                value.contentEquals(StrapGatt.ENABLE_NOTIFICATION)
            if (enablingHeart && !heartOn) {
                heartOn = true
                handler.postDelayed(heart, 1_000L)
            }
        }
        return true
    }

    override fun writeCharacteristic(characteristic: UUID, value: ByteArray, withResponse: Boolean): Boolean {
        if (closed || !connected) return false
        handler.post {
            if (closed) return@post
            listener?.onCharacteristicWrite(GattStatus.SUCCESS)
            if (characteristic != StrapGatt.COMMAND) return@post
            val replies = engine.onCommand(value)
            val startHistory = engine.consumeArm()
            for (frame in replies) {
                listener?.onNotification(StrapGatt.RESPONSE_NOTIFY, frame)
            }
            engine.takeChargeFrame()?.let { frame ->
                listener?.onNotification(StrapGatt.EVENT_NOTIFY, frame)
            }
            if (startHistory) scheduleHistory()
        }
        return true
    }

    override fun readCharacteristic(characteristic: UUID): Boolean {
        if (closed || !connected) return false
        val value = when (characteristic) {
            StrapGatt.FIRMWARE_REVISION -> SimulatedStrapEngine.FIRMWARE.encodeToByteArray()
            StrapGatt.BATTERY_LEVEL -> byteArrayOf(engine.batteryPercent.toByte())
            else -> return false
        }
        handler.post {
            if (closed) return@post
            listener?.onCharacteristicRead(characteristic, GattStatus.SUCCESS, value)
        }
        return true
    }

    override fun disconnect() {
        handler.post { drop(GattStatus.SUCCESS) }
    }

    override fun close() {
        closed = true
        heartOn = false
        handler.removeCallbacksAndMessages(null)
        connected = false
        listener = null
    }

    private fun scheduleHistory() {
        if (pumping || closed) return
        pumping = true
        handler.post { pumpHistory() }
    }

    private fun pumpHistory() {
        if (closed) return
        when (val pull = engine.pull(200)) {
            is StreamPull.Done -> pumping = false
            is StreamPull.Frames -> {
                for (frame in pull.frames) {
                    listener?.onNotification(StrapGatt.DATA_NOTIFY, frame)
                }
                handler.postDelayed({ pumpHistory() }, 10L)
            }
            is StreamPull.Disconnect -> {
                for (frame in pull.frames) {
                    listener?.onNotification(StrapGatt.DATA_NOTIFY, frame)
                }
                handler.post { drop(GattStatus.LINK_DROPPED) }
                pumping = false
            }
        }
    }

    private fun drop(status: Int) {
        if (!connected || closed) return
        connected = false
        heartOn = false
        listener?.onConnectionState(status, false)
    }
}
