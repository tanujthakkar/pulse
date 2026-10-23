package app.pulse.feature.sync

import androidx.lifecycle.ViewModel
import app.pulse.data.DataBuildInfo
import app.pulse.link.LinkBuildInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Skeleton view model. Screen behavior arrives in a later milestone. */
@HiltViewModel
class SyncViewModel @Inject constructor() : ViewModel() {
    val title: String = "Sync"
    val modulePins: String = "${LinkBuildInfo.SPEC}:${DataBuildInfo.SPEC}"
    val body: String = "History sync status will live here."
}
