package app.pulse.ble

import java.util.UUID

internal object GattStatus {
    const val SUCCESS: Int = 0
    const val TIMEOUT: Int = -1
    const val START_FAILED: Int = -2
    const val MISSING: Int = -3
    const val LINK_DROPPED: Int = -4
}

internal data class DiscoveredGatt(
    val services: Set<UUID>,
    val characteristics: Set<UUID>,
)

internal interface RadioListener {
    fun onConnectionState(status: Int, connected: Boolean)

    fun onServices(status: Int, discovered: DiscoveredGatt)

    fun onMtu(status: Int, mtu: Int)

    fun onDescriptorWrite(status: Int)

    fun onCharacteristicWrite(status: Int)

    fun onNotification(characteristic: UUID, value: ByteArray)
}

/**
 * One platform GATT client.
 *
 * A start method returns false when the operation did not start and no
 * callback will follow. [setNotify] is local. Completions are reported to
 * [listener] after the start method returns. [close] releases the client and
 * is safe more than once; skipping it leaks the platform object.
 */
internal interface GattRadio {
    var listener: RadioListener?

    fun connect(address: String): Boolean

    fun discoverServices(): Boolean

    fun requestMtu(mtu: Int): Boolean

    fun setNotify(characteristic: UUID, enable: Boolean)

    fun writeCccd(characteristic: UUID, value: ByteArray): Boolean

    fun writeCharacteristic(characteristic: UUID, value: ByteArray, withResponse: Boolean): Boolean

    fun disconnect()

    fun close()
}

internal fun interface OpLogger {
    fun log(line: String)
}
