package app.pulse.link

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import app.pulse.ble.BleClients
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Foreground service for a strap link.
 *
 * The manifest sets the foreground service type to connectedDevice. While the
 * link is scanning, bonding, connecting, or linked, this service calls
 * [startForeground] with an ongoing Pulse notification. A user disconnect or
 * any other clean teardown removes that notification and stops the service.
 *
 * Hilt supplies the dependencies through [LinkServiceEntryPoint]. This service
 * retains [HistorySyncHost] for its whole lifetime. That host owns
 * [app.pulse.sync.HistorySyncDriver] and the history engine inside it. Nothing
 * in the Activity lifecycle cancels an attempt: pause and stop are not
 * observed here. The engine keeps running until the attempt finishes or the
 * strap link drops.
 */
class StrapLinkService : Service() {
    /** The history host this service keeps alive. Null before [onCreate]. */
    internal var retainedSync: HistorySyncHost? = null
        private set

    private var link: StrapLink? = null
    private var sync: HistorySyncHost? = null
    private var notifications: LinkNotifications? = null
    private var onForegroundRejected: () -> Unit = {}
    private var destroyed = false
    private var foreground = false
    private var linkListener: ((LinkSnapshot) -> Unit)? = null
    private var removeSyncWatch: (() -> Unit)? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val deps = LinkServiceHooks.deps ?: depsFromHilt()
        link = deps.link
        sync = deps.sync
        notifications = deps.notifications
        onForegroundRejected = deps.onForegroundRejected
        retainedSync = deps.sync
        linkListener = { render() }
        link?.watch(linkListener!!)
        if (destroyed) return
        removeSyncWatch = sync?.watch { render() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!destroyed) render()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        linkListener?.let { listener -> link?.unwatch(listener) }
        linkListener = null
        removeSyncWatch?.invoke()
        removeSyncWatch = null
        dropForeground()
        super.onDestroy()
    }

    private fun render() {
        if (destroyed) return
        val currentLink = link ?: return
        val currentSync = sync ?: return
        val notes = notifications ?: return
        val stay = LinkForegroundPolicy.shouldRun(currentLink.snapshot().phase)
        val notification = notes.build(currentLink.snapshot(), currentSync.running.value)
        startInForeground(notification)
        if (!stay) {
            dropForeground()
            stopSelf()
        }
    }

    private fun startInForeground(notification: android.app.Notification) {
        if (!LinkRuntimePermission.bluetoothGranted(this)) {
            Log.i(BleClients.LOG_TAG, "foreground link skipped: bluetooth permission is missing")
            onForegroundRejected()
            stopSelf()
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    LinkNotifications.NOTE_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                startForeground(LinkNotifications.NOTE_ID, notification)
            }
            foreground = true
        } catch (error: SecurityException) {
            Log.i(BleClients.LOG_TAG, "foreground link was not allowed: ${error.message}")
            onForegroundRejected()
            stopSelf()
        }
    }

    private fun dropForeground() {
        if (!foreground) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
    }

    private fun depsFromHilt(): LinkServiceDeps {
        val entry = EntryPointAccessors.fromApplication(
            applicationContext,
            LinkServiceEntryPoint::class.java,
        )
        val host = entry.connectedLinkHost()
        return LinkServiceDeps(
            link = entry.strapLink(),
            sync = entry.historySyncHost(),
            notifications = entry.linkNotifications(),
            onForegroundRejected = host::foregroundRejected,
        )
    }
}

internal data class LinkServiceDeps(
    val link: StrapLink,
    val sync: HistorySyncHost,
    val notifications: LinkNotifications,
    val onForegroundRejected: () -> Unit = {},
)

/**
 * Test seam. Production leaves this null and reads [LinkServiceEntryPoint].
 */
internal object LinkServiceHooks {
    var deps: LinkServiceDeps? = null
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface LinkServiceEntryPoint {
    fun strapLink(): StrapLink

    fun historySyncHost(): HistorySyncHost

    fun linkNotifications(): LinkNotifications

    fun connectedLinkHost(): ConnectedLinkHost
}
