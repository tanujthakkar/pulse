package app.pulse.feature.live

import androidx.lifecycle.ViewModel
import app.pulse.data.DataBuildInfo
import app.pulse.link.LinkBuildInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Skeleton view model. Screen behavior arrives in a later milestone. */
@HiltViewModel
class LiveViewModel @Inject constructor() : ViewModel() {
    val title: String = "Live"
    val modulePins: String = "${LinkBuildInfo.SPEC}:${DataBuildInfo.SPEC}"
    val body: String = "Live heart rate will live here."
}
