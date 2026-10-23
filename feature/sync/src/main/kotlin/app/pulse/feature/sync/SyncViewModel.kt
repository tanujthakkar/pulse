package app.pulse.feature.sync

import androidx.lifecycle.ViewModel
import app.pulse.data.DataBuildInfo
import app.pulse.link.HistorySyncHost
import app.pulse.link.LinkBuildInfo
import app.pulse.sync.SyncActivity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * Manual history sync.
 *
 * The button starts one attempt on [HistorySyncHost]. [activity] is the
 * session state, the live chunk and record counts, and the last outcome with
 * its time. That host, and the history engine inside it, are retained by
 * [app.pulse.link.StrapLinkService], so pausing or stopping the Activity does
 * not cancel the attempt. The button stays disabled while the attempt runs.
 * Acknowledgements stay behind the metadata gate.
 */
@HiltViewModel
class SyncViewModel @Inject constructor(
    private val sync: HistorySyncHost,
) : ViewModel() {
    val title: String = "Sync"

    /** Keeps the link and data modules on the sync classpath. */
    val modulePins: String = "${LinkBuildInfo.SPEC}:${DataBuildInfo.SPEC}"

    val activity: StateFlow<SyncActivity> = sync.activity

    fun syncNow() {
        sync.syncNow()
    }
}
