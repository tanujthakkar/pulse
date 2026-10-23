package app.pulse.feature.connect

import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

internal class AndroidRadioProbe(
    private val context: Context,
) : RadioProbe {
    override fun read(activity: Activity): RadioSnapshot {
        val sdk = Build.VERSION.SDK_INT
        val required = LinkPermission.requiredFor(sdk)
        val granted = required.filter { permission ->
            activity.checkSelfPermission(permission.androidPermission) ==
                PackageManager.PERMISSION_GRANTED
        }.toSet()
        val rationale = required.filter { permission ->
            permission !in granted &&
                activity.shouldShowRequestPermissionRationale(permission.androidPermission)
        }.toSet()
        return RadioSnapshot(
            sdkInt = sdk,
            granted = granted,
            rationale = rationale,
            bluetoothEnabled = bluetoothEnabled(granted),
        )
    }

    private fun bluetoothEnabled(granted: Set<LinkPermission>): Boolean {
        if (LinkPermission.Connect !in granted) return false
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        return adapter?.isEnabled == true
    }
}
