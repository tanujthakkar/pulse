package app.pulse

import android.app.Application
import app.pulse.link.AutoSyncCoordinator
import app.pulse.link.ConnectedLinkHost
import app.pulse.link.StrapLink
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class PulseApp : Application() {
    @Inject lateinit var connectedLink: ConnectedLinkHost
    @Inject lateinit var autoSync: AutoSyncCoordinator
    @Inject lateinit var strapLink: StrapLink

    override fun onCreate() {
        super.onCreate()
        // The host watches the strap link from the moment it is created.
        // Touching it here arms that watch before any Activity can start,
        // pause, or leave.
        connectedLink.arm()
        autoSync.start()
        autostartSimulatedStrap(this, strapLink)
    }
}
