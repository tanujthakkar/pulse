package app.pulse.link

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

/**
 * Bluetooth grants required before a connectedDevice foreground service.
 *
 * Android 14 and later throw [SecurityException] from [android.app.Service.startForeground]
 * when this service type is used without [Manifest.permission.BLUETOOTH_CONNECT] and
 * [Manifest.permission.BLUETOOTH_SCAN]. Notification permission is requested with
 * them, but a missing notification grant does not by itself make [startForeground] throw.
 */
object LinkRuntimePermission {
    fun bluetoothGranted(context: Context): Boolean {
        val app = context.applicationContext
        return app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED &&
            app.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED
    }
}
