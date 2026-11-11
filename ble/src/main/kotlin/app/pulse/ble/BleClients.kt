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
        return StrapScanner(PlatformRadios.scanPort(context), events)
    }

    fun gattSession(
        context: Context,
        events: SessionEvents,
        deferProprietaryNotifies: Boolean = false,
        frameTap: FrameTap? = null,
    ): GattSession {
        val appContext = context.applicationContext
        return GattSession(
            radio = PlatformRadios.gattRadio(appContext),
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
            port = PlatformRadios.bondPort(appContext),
            events = events,
        )
    }
}
