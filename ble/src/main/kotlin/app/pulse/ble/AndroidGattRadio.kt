package app.pulse.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.util.UUID

/**
 * [GattRadio] on [BluetoothGatt]. One instance owns one client. [close] drops
 * the reference and calls through to the platform so a disconnect cycle cannot
 * leave the client registered.
 */
internal class AndroidGattRadio(
    private val context: Context,
) : GattRadio {
    private val lock = Any()
    private var gatt: BluetoothGatt? = null

    override var listener: RadioListener? = null

    // The API 33 callback class is instantiated only on API 33+, so API 31
    // does not load an override the platform class does not have yet.
    private val callback: BluetoothGattCallback = if (Build.VERSION.SDK_INT >= 33) {
        Callback33()
    } else {
        Callback()
    }

    private open inner class Callback : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            listener?.onConnectionState(
                status = status,
                connected = newState == BluetoothProfile.STATE_CONNECTED,
            )
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val services = linkedSetOf<UUID>()
            val characteristics = linkedSetOf<UUID>()
            if (status == BluetoothGatt.GATT_SUCCESS) {
                for (service in gatt.services.orEmpty()) {
                    services += service.uuid
                    for (characteristic in service.characteristics.orEmpty()) {
                        characteristics += characteristic.uuid
                    }
                }
            }
            listener?.onServices(status, DiscoveredGatt(services, characteristics))
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            listener?.onMtu(status, mtu)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: android.bluetooth.BluetoothGattDescriptor,
            status: Int,
        ) {
            listener?.onDescriptorWrite(status)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            listener?.onCharacteristicWrite(status)
        }

        @Deprecated("API 33 passes the value separately. Used on API 31 and 32.")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            val value = characteristic.value?.copyOf() ?: ByteArray(0)
            listener?.onNotification(characteristic.uuid, value)
        }
    }

    @SuppressLint("NewApi")
    private inner class Callback33 : Callback() {
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            listener?.onNotification(characteristic.uuid, value.copyOf())
        }
    }

    override fun connect(address: String): Boolean {
        if (!connectGranted()) return false
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return false
        val adapter = manager.adapter ?: return false
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val client = try {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (_: SecurityException) {
            null
        } ?: return false
        synchronized(lock) { gatt = client }
        return true
    }

    override fun discoverServices(): Boolean {
        val client = current() ?: return false
        if (!connectGranted()) return false
        return try {
            client.discoverServices()
        } catch (_: SecurityException) {
            false
        }
    }

    override fun requestMtu(mtu: Int): Boolean {
        val client = current() ?: return false
        if (!connectGranted()) return false
        return try {
            client.requestMtu(mtu)
        } catch (_: SecurityException) {
            false
        }
    }

    override fun setNotify(characteristic: UUID, enable: Boolean) {
        val client = current() ?: return
        if (!connectGranted()) return
        val target = findCharacteristic(client, characteristic) ?: return
        try {
            client.setCharacteristicNotification(target, enable)
        } catch (_: SecurityException) {
            // The descriptor write that follows reports the failure.
        }
    }

    override fun writeCccd(characteristic: UUID, value: ByteArray): Boolean {
        val client = current() ?: return false
        if (!connectGranted()) return false
        val target = findCharacteristic(client, characteristic) ?: return false
        val descriptor = target.getDescriptor(StrapGatt.CCCD) ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                client.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = value
                @Suppress("DEPRECATION")
                client.writeDescriptor(descriptor)
            }
        } catch (_: SecurityException) {
            false
        }
    }

    override fun writeCharacteristic(
        characteristic: UUID,
        value: ByteArray,
        withResponse: Boolean,
    ): Boolean {
        val client = current() ?: return false
        if (!connectGranted()) return false
        val target = findCharacteristic(client, characteristic) ?: return false
        val writeType = if (withResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                client.writeCharacteristic(target, value, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                target.writeType = writeType
                @Suppress("DEPRECATION")
                target.value = value
                @Suppress("DEPRECATION")
                client.writeCharacteristic(target)
            }
        } catch (_: SecurityException) {
            false
        }
    }

    override fun disconnect() {
        val client = current() ?: return
        if (!connectGranted()) return
        try {
            client.disconnect()
        } catch (_: SecurityException) {
            // The session's operation deadline still closes the client.
        }
    }

    @SuppressLint("MissingPermission")
    override fun close() {
        val client = synchronized(lock) { gatt.also { gatt = null } } ?: return
        try {
            client.close()
        } catch (_: SecurityException) {
            // The permission was revoked after the client was registered.
            // close() is still attempted above so the client is not leaked
            // when the permission check would have skipped it.
        }
    }

    private fun current(): BluetoothGatt? = synchronized(lock) { gatt }

    private fun connectGranted(): Boolean {
        return context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun findCharacteristic(
        client: BluetoothGatt,
        characteristic: UUID,
    ): BluetoothGattCharacteristic? {
        for (service in client.services.orEmpty()) {
            val found = service.getCharacteristic(characteristic)
            if (found != null) return found
        }
        return null
    }
}
