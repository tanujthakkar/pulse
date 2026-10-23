package app.pulse.feature.connect

import android.app.Activity
import androidx.lifecycle.ViewModel
import app.pulse.data.DataBuildInfo
import app.pulse.link.LinkBuildInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Gates the Connect destination on runtime grants and the Bluetooth radio.
 *
 * Pairing itself is a later milestone. This model only decides which gate
 * screen is showing.
 */
@HiltViewModel
class ConnectViewModel @Inject constructor(
    private val memory: PermissionPromptMemory,
    private val probe: RadioProbe,
) : ViewModel() {
    private val _ui = MutableStateFlow<ConnectUi>(ConnectUi.Checking)
    val ui: StateFlow<ConnectUi> = _ui.asStateFlow()

    /** Keeps the link and data modules on the Connect classpath. */
    val modulePins: String = "${LinkBuildInfo.SPEC}:${DataBuildInfo.SPEC}"

    fun refresh(activity: Activity) {
        publish(probe.read(activity))
    }

    fun onPermissionResult(
        activity: Activity,
        requested: List<LinkPermission>,
        result: Map<String, Boolean>,
    ) {
        onPermissionResult(probe.read(activity), requested, result)
    }

    internal fun onPermissionResult(
        snapshot: RadioSnapshot,
        requested: Collection<LinkPermission>,
        result: Map<String, Boolean>,
    ) {
        memory.markPrompted(requested)
        val granted = snapshot.granted.toMutableSet()
        result.forEach { (name, allowed) ->
            val permission = LinkPermission.fromAndroidPermission(name) ?: return@forEach
            if (allowed) granted += permission else granted -= permission
        }
        publish(snapshot.copy(granted = granted))
    }

    internal fun publish(snapshot: RadioSnapshot) {
        _ui.value = ConnectGatePolicy.evaluate(snapshot, memory.prompted())
    }
}
