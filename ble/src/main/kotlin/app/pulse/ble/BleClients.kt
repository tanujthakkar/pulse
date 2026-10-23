package app.pulse.ble

import android.content.Context
import android.util.Log

/**
 * Platform entry points for the scanner and the GATT session.
 *
 * Pass any context; the application context is retained. Permission prompts
 * are a separate step: these calls fail cleanly when scan or connect has not
 * been granted.
 */
object BleClients {
    const val LOG_TAG: String = "PulseGatt"

    fun strapScanner(context: Context, events: ScanEvents): StrapScanner {
        return StrapScanner(AndroidScanPort(context.applicationContext), events)
    }

    fun gattSession(
        context: Context,
        events: SessionEvents,
        deferProprietaryNotifies: Boolean = false,
        frameTap: FrameTap? = null,
    ): GattSession {
        val appContext = context.applicationContext
        return GattSession(
            radio = AndroidGattRadio(appContext),
            events = events,
            alarms = SystemAlarmClock(),
            log = OpLogger { line -> Log.i(LOG_TAG, line) },
            deferProprietaryNotifies = deferProprietaryNotifies,
            frameTap = frameTap,
        )
    }

    fun bondController(context: Context, events: BondEvents): BondController {
        val appContext = context.applicationContext
        return BondController(
            port = AndroidBondPort(appContext),
            events = events,
        )
    }
}
