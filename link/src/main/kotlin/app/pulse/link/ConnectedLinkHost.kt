package app.pulse.link

import android.content.Context
import android.content.Intent
import android.util.Log
import app.pulse.ble.BleClients
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts and stops [StrapLinkService] from [StrapLink] phases.
 *
 * Created with the process ([app.pulse.PulseApp]) so the watch outlives every
 * Activity. A disconnect or clean teardown stops the service. An unexpected
 * drop keeps it, because the link goes back to scanning on its own.
 */
@Singleton
class ConnectedLinkHost internal constructor(
    private val link: StrapLink,
    private val promote: () -> Unit,
    private val release: () -> Unit,
    private val log: (String) -> Unit,
    private val mayPromote: () -> Boolean = { true },
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        link: StrapLink,
    ) : this(
        link = link,
        promote = { startStrapLinkService(context) },
        release = { stopStrapLinkService(context) },
        log = { line -> Log.i(BleClients.LOG_TAG, line) },
        mayPromote = { LinkRuntimePermission.bluetoothGranted(context) },
    )

    private var armed = false
    private var running = false

    /** True after this host has asked the service to stay in the foreground. */
    val holding: Boolean
        get() = running

    init {
        arm()
    }

    /**
     * Installs the phase watch. [init] already calls this. [app.pulse.PulseApp]
     * calls it again so Hilt creates the singleton during application start.
     */
    fun arm() {
        if (armed) return
        armed = true
        link.watch(::onPhase)
    }

    /**
     * Starts the foreground service after a grant, if the link already wants it.
     *
     * A phase change that happened while Bluetooth permission was missing does
     * not fire again on its own.
     */
    fun onPermissionsChanged() {
        onPhase(link.snapshot())
    }

    /**
     * The service refused [startForeground] because Bluetooth was not granted.
     * [running] is cleared so a later grant can try again.
     */
    internal fun foregroundRejected() {
        running = false
        log("foreground link waiting for bluetooth permission")
    }

    private fun onPhase(snapshot: LinkSnapshot) {
        val wanted = LinkForegroundPolicy.shouldRun(snapshot.phase) && mayPromote()
        if (wanted == running) return
        if (wanted) {
            running = true
            try {
                promote()
                if (!running) return
                log("foreground link start ${snapshot.phase}")
            } catch (error: IllegalStateException) {
                running = false
                log("foreground link start was not allowed: ${error.message}")
            } catch (error: SecurityException) {
                running = false
                log("foreground link start was not allowed: ${error.message}")
            }
        } else {
            running = false
            log("foreground link stop ${snapshot.phase}")
            release()
        }
    }
}

internal fun startStrapLinkService(context: Context) {
    val app = context.applicationContext
    app.startForegroundService(Intent(app, StrapLinkService::class.java))
}

internal fun stopStrapLinkService(context: Context) {
    val app = context.applicationContext
    app.stopService(Intent(app, StrapLinkService::class.java))
}
