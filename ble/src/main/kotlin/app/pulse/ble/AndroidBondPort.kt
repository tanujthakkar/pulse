package app.pulse.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build

/**
 * Platform bond port. [createBond] uses the standard OS bond. There is no
 * public API to cancel an in-flight bond, so [cancel] only forgets the
 * address we asked for. The state machine stops issuing new requests.
 */
internal class AndroidBondPort(
    private val context: Context,
) : BondPort {
    override var onUpdate: ((address: String, bond: DeviceBond, refused: Boolean) -> Unit)? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device = deviceExtra(intent) ?: return
            val current = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
            val previous = intent.getIntExtra(
                BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,
                BluetoothDevice.ERROR,
            )
            val reason = intent.getIntExtra(BondReason.REASON_EXTRA, 0)
            val update = BondChanges.classify(previous, current, reason) ?: return
            onUpdate?.invoke(device.address, update.bond, update.refused)
        }
    }

    init {
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    @SuppressLint("MissingPermission")
    override fun state(address: String): DeviceBond {
        if (!connectGranted()) return DeviceBond.ABSENT
        val device = remote(address) ?: return DeviceBond.ABSENT
        return try {
            BondChanges.fromPlatform(device.bondState)
        } catch (_: SecurityException) {
            DeviceBond.ABSENT
        }
    }

    @SuppressLint("MissingPermission")
    override fun createBond(address: String): Boolean {
        if (!connectGranted()) return false
        val device = remote(address) ?: return false
        return try {
            device.createBond()
        } catch (_: SecurityException) {
            false
        }
    }

    override fun cancel(address: String) = Unit

    private fun remote(address: String): BluetoothDevice? {
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return null
        val adapter = manager.adapter ?: return null
        return try {
            adapter.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun connectGranted(): Boolean {
        return context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun deviceExtra(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }
}
