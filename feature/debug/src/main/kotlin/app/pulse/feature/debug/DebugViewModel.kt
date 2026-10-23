package app.pulse.feature.debug

import androidx.lifecycle.ViewModel
import app.pulse.data.DataBuildInfo
import app.pulse.link.LinkBuildInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Skeleton view model. Screen behavior arrives in a later milestone. */
@HiltViewModel
class DebugViewModel @Inject constructor() : ViewModel() {
    val title: String = "Debug"
    val modulePins: String = "${LinkBuildInfo.SPEC}:${DataBuildInfo.SPEC}"
    val body: String = "Raw frame inspection will live here."
}
