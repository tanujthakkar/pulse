package app.pulse.feature.connect

import android.app.Activity
import androidx.lifecycle.ViewModel
import app.pulse.data.DataBuildInfo
import app.pulse.link.LinkBuildInfo
import app.pulse.link.StrapLink
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Gates Connect on runtime grants and the Bluetooth radio, then runs
 * [StrapLink] once pairing is allowed to start.
 *
 * Stopping the link is explicit ([stopLink], or a gate that is not ready).
 * Clearing this screen does not disconnect. [app.pulse.link.StrapLinkService]
 * keeps the session, and the history engine it retains, while the link is up.
 */
@HiltViewModel
class ConnectViewModel @Inject constructor(
    private val memory: PermissionPromptMemory,
    private val probe: RadioProbe,
    private val link: StrapLink,
) : ViewModel() {
    private val _ui = MutableStateFlow<ConnectUi>(ConnectUi.Checking)
    val ui: StateFlow<ConnectUi> = _ui.asStateFlow()

    private val _linkPanel = MutableStateFlow(LinkPanel.from(link.snapshot()))
    val linkPanel: StateFlow<LinkPanel> = _linkPanel.asStateFlow()

    /** Keeps the link and data modules on the Connect classpath. */
    val modulePins: String = "${LinkBuildInfo.SPEC}:${DataBuildInfo.SPEC}"

    init {
        link.watch { _linkPanel.value = LinkPanel.from(it) }
    }

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

    fun retryLink() {
        link.retry()
    }

    fun stopLink() {
        link.stop()
    }

    fun repairLink() {
        link.repair()
    }

    internal fun publish(snapshot: RadioSnapshot) {
        val next = ConnectGatePolicy.evaluate(snapshot, memory.prompted())
        val wasReady = _ui.value == ConnectUi.Ready
        _ui.value = next
        if (next == ConnectUi.Ready) {
            if (!wasReady) link.start()
        } else {
            link.stop()
        }
    }
}
