package app.pulse.ble

import java.util.UUID

internal class FakeGattRadio : GattRadio {
    override var listener: RadioListener? = null

    var failConnectStart: Boolean = false

    private val lock = Any()
    private var inFlightOps = 0
    private var inFlightRead: UUID? = null
    var maxInFlight: Int = 0
        private set

    val started: MutableList<String> = mutableListOf()
    val cccdWrites: MutableList<Pair<UUID, ByteArray>> = mutableListOf()
    val writes: MutableList<WriteCapture> = mutableListOf()
    val reads: MutableList<UUID> = mutableListOf()
    var requestedMtu: Int? = null
    var connectAddress: String? = null
    var closeCount: Int = 0
    var disconnectCount: Int = 0

    data class WriteCapture(
        val characteristic: UUID,
        val value: ByteArray,
        val withResponse: Boolean,
    )

    override fun connect(address: String): Boolean {
        connectAddress = address
        if (failConnectStart) return false
        enter("connect")
        return true
    }

    fun succeedConnect() {
        leave()
        listener?.onConnectionState(GattStatus.SUCCESS, connected = true)
    }

    fun failConnect(status: Int) {
        leave()
        listener?.onConnectionState(status, connected = false)
    }

    override fun discoverServices(): Boolean {
        enter("discover")
        return true
    }

    fun succeedDiscover(services: Set<UUID>, characteristics: Set<UUID>) {
        leave()
        listener?.onServices(GattStatus.SUCCESS, DiscoveredGatt(services, characteristics))
    }

    override fun requestMtu(mtu: Int): Boolean {
        enter("requestMtu")
        requestedMtu = mtu
        return true
    }

    fun succeedMtu(mtu: Int) {
        leave()
        listener?.onMtu(GattStatus.SUCCESS, mtu)
    }

    override fun setNotify(characteristic: UUID, enable: Boolean) {
        synchronized(lock) {
            inFlightOps += 1
            if (inFlightOps > maxInFlight) maxInFlight = inFlightOps
            started += "setNotify:$characteristic:$enable"
            inFlightOps -= 1
        }
    }

    override fun writeCccd(characteristic: UUID, value: ByteArray): Boolean {
        enter("cccd:$characteristic")
        cccdWrites += characteristic to value.copyOf()
        return true
    }

    fun succeedCccd() {
        leave()
        listener?.onDescriptorWrite(GattStatus.SUCCESS)
    }

    fun failCccd(status: Int) {
        leave()
        listener?.onDescriptorWrite(status)
    }

    override fun writeCharacteristic(
        characteristic: UUID,
        value: ByteArray,
        withResponse: Boolean,
    ): Boolean {
        enter("write:$characteristic")
        writes += WriteCapture(characteristic, value.copyOf(), withResponse)
        return true
    }

    fun succeedWrite() {
        leave()
        listener?.onCharacteristicWrite(GattStatus.SUCCESS)
    }

    override fun readCharacteristic(characteristic: UUID): Boolean {
        enter("read:$characteristic")
        reads += characteristic
        inFlightRead = characteristic
        return true
    }

    fun succeedRead(value: ByteArray, status: Int = GattStatus.SUCCESS) {
        val characteristic = checkNotNull(inFlightRead)
        inFlightRead = null
        leave()
        listener?.onCharacteristicRead(characteristic, status, value)
    }

    override fun disconnect() {
        enter("disconnect")
        disconnectCount += 1
    }

    fun completeDisconnect(status: Int = GattStatus.SUCCESS) {
        leave()
        listener?.onConnectionState(status, connected = false)
    }

    /** The link dropped while some other operation was outstanding. */
    fun dropLink(status: Int) {
        synchronized(lock) {
            if (inFlightOps > 0) {
                inFlightOps -= 1
            }
        }
        listener?.onConnectionState(status, connected = false)
    }

    fun emit(characteristic: UUID, value: ByteArray) {
        listener?.onNotification(characteristic, value)
    }

    override fun close() {
        closeCount += 1
    }

    private fun enter(name: String) {
        synchronized(lock) {
            inFlightOps += 1
            if (inFlightOps > maxInFlight) maxInFlight = inFlightOps
            started += name
        }
    }

    private fun leave() {
        synchronized(lock) {
            check(inFlightOps > 0) { "radio completion with nothing in flight; started=$started" }
            inFlightOps -= 1
        }
    }
}
