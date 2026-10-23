package app.pulse.link

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Foreground link service placeholder.
 *
 * The manifest reserves the connected-device service type. Starting the service
 * and promoting it to the foreground is a later milestone; nothing in the
 * skeleton starts this service.
 */
class StrapLinkService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
