package app.pulse.ble

import android.content.Context

/**
 * Release builds always use the platform Bluetooth client.
 *
 * The simulated strap is not in this source set.
 */
internal object PlatformRadios {
    fun scanPort(context: Context): ScanPort = AndroidScanPort(context.applicationContext)

    fun bondPort(context: Context): BondPort = AndroidBondPort(context.applicationContext)

    fun gattRadio(context: Context): GattRadio = AndroidGattRadio(context.applicationContext)
}
