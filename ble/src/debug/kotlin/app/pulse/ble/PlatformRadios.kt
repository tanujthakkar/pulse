package app.pulse.ble

import android.content.Context

/**
 * Debug builds read the simulation flag when a scan, bond, or GATT client
 * is created. The flag defaults to off, so a normal debug install still
 * uses the platform radio.
 */
internal object PlatformRadios {
    fun scanPort(context: Context): ScanPort = SelectingScanPort(context)

    fun bondPort(context: Context): BondPort = SelectingBondPort(context)

    fun gattRadio(context: Context): GattRadio =
        if (SimulatedStrap.isEnabled(context)) {
            SimulatedGattRadio(context)
        } else {
            AndroidGattRadio(context.applicationContext)
        }
}
