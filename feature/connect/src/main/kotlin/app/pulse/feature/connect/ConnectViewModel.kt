package app.pulse.feature.connect

import androidx.lifecycle.ViewModel
import app.pulse.data.DataBuildInfo
import app.pulse.link.LinkBuildInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Skeleton view model. Screen behavior arrives in a later milestone. */
@HiltViewModel
class ConnectViewModel @Inject constructor() : ViewModel() {
    val title: String = "Connect"
    val modulePins: String = "${LinkBuildInfo.SPEC}:${DataBuildInfo.SPEC}"
    val body: String = "Pairing and connection will live here."
}
