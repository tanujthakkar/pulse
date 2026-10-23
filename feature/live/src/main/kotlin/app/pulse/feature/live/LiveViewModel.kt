package app.pulse.feature.live

import androidx.lifecycle.ViewModel
import app.pulse.ble.LiveHeartRate
import app.pulse.data.DataBuildInfo
import app.pulse.link.LinkBuildInfo
import app.pulse.link.LinkPhase
import app.pulse.link.LiveHeartRateFeed
import app.pulse.link.StrapLink
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shows the in-memory heart-rate feed. Connect owns starting and stopping
 * the link. Nothing here is written to disk.
 */
@HiltViewModel
class LiveViewModel @Inject constructor(
    link: StrapLink,
    heartRate: LiveHeartRateFeed,
) : ViewModel() {
    private val phases = MutableStateFlow(link.snapshot().phase)
    val phase: StateFlow<LinkPhase> = phases.asStateFlow()
    val latest: StateFlow<LiveHeartRate?> = heartRate.latest

    /** Keeps the link and data modules on the Live classpath. */
    val modulePins: String = "${LinkBuildInfo.SPEC}:${DataBuildInfo.SPEC}"

    init {
        link.watch { phases.value = it.phase }
    }
}
