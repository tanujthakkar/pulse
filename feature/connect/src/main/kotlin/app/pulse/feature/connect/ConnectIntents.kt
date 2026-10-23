package app.pulse.feature.connect

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** App details screen, where a denied Bluetooth grant can be turned back on. */
fun applicationSettingsIntent(packageName: String): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", packageName, null)
    }

/** System dialog that asks the user to turn Bluetooth on. */
fun enableBluetoothIntent(): Intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
