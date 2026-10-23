package app.pulse.ble

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

/**
 * Scan with a single service filter. The advertised name is forwarded on the
 * sighting and is not part of the filter.
 */
internal class AndroidScanPort(
    private val context: Context,
) : ScanPort {
    private val lock = Any()
    private var callback: ScanCallback? = null
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null

    override fun start(
        service: UUID,
        onSighting: (StrapSighting) -> Unit,
        onFailed: (Int) -> Unit,
    ) {
        if (!hasScan()) {
            onFailed(ScanFailure.PERMISSION)
            return
        }
        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            onFailed(ScanFailure.ADAPTER_OFF)
            return
        }
        val bleScanner = adapter.bluetoothLeScanner
        if (bleScanner == null) {
            onFailed(ScanFailure.ADAPTER_OFF)
            return
        }
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                if (!hasConnect()) return
                Log.i(BleClients.LOG_TAG, "sighting ${device.address} rssi=${result.rssi}")
                onSighting(
                    StrapSighting(
                        address = device.address,
                        rssi = result.rssi,
                        advertisedName = result.scanRecord?.deviceName,
                    ),
                )
            }

            override fun onScanFailed(errorCode: Int) {
                onFailed(errorCode)
            }
        }
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(service))
                .build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        synchronized(lock) {
            stopLocked()
            callback = scanCallback
            scanner = bleScanner
        }
        Log.i(BleClients.LOG_TAG, "scan start filter=$service")
        try {
            bleScanner.startScan(filters, settings, scanCallback)
        } catch (_: SecurityException) {
            onFailed(ScanFailure.PERMISSION)
        } catch (_: IllegalStateException) {
            onFailed(ScanFailure.ADAPTER_OFF)
        }
    }

    override fun stop() {
        synchronized(lock) { stopLocked() }
    }

    private fun stopLocked() {
        val active = callback
        val current = scanner
        callback = null
        scanner = null
        if (active == null || current == null || !hasScan()) return
        try {
            current.stopScan(active)
            Log.i(BleClients.LOG_TAG, "scan stop")
        } catch (_: SecurityException) {
        } catch (_: IllegalStateException) {
        }
    }

    private fun hasScan(): Boolean {
        return context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun hasConnect(): Boolean {
        return context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }
}
