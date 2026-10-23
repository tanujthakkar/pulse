package app.pulse.feature.debug

import androidx.lifecycle.ViewModel
import app.pulse.data.DataBuildInfo
import app.pulse.data.DeviceDirectory
import app.pulse.data.DeviceRow
import app.pulse.link.LinkBuildInfo
import app.pulse.link.LinkSnapshot
import app.pulse.link.StrapLink
import app.pulse.link.commandBatteryLine
import app.pulse.link.firmwareNote
import app.pulse.link.handshakeLine
import app.pulse.link.helloFirmwareLine
import app.pulse.link.informationFirmwareLine
import app.pulse.link.serviceBatteryLine
import app.pulse.wire.codec.FrameNote
import app.pulse.wire.codec.FrameRing
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.OutputStream
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Device and firmware lines for the debug screen.
 *
 * Hello identity blocks are not on [LinkSnapshot] and are not stored, so this
 * text cannot print them.
 */
fun debugBody(snapshot: LinkSnapshot, stored: DeviceRow?): String {
    val lines = mutableListOf("Identity blocks from hello are redacted and are not stored.")
    handshakeLine(snapshot.hello, snapshot.heartRateLive)?.let { lines += it }
    helloFirmwareLine(snapshot.helloFirmware)?.let { lines += it }
    informationFirmwareLine(snapshot.informationFirmware)?.let { lines += it }
    firmwareNote(snapshot.helloFirmware, snapshot.informationFirmware)?.let { lines += it }
    commandBatteryLine(snapshot.commandBatteryPercent)?.let { lines += it }
    serviceBatteryLine(snapshot.serviceBatteryPercent)?.let { lines += it }
    if (stored == null) {
        lines += "No device row stored."
    } else {
        lines += "Stored device ${stored.address}."
        stored.helloFirmware?.let { lines += "Stored hello firmware: $it" }
        stored.informationFirmware?.let { lines += "Stored device information firmware: $it" }
        stored.commandBatteryPercent?.let { lines += "Stored battery (command 26): $it%" }
        stored.serviceBatteryPercent?.let { lines += "Stored battery (standard battery service): $it%" }
    }
    return lines.joinToString(separator = "\n")
}

/**
 * Device lines plus the in-memory frame ring.
 *
 * Hello identity blocks are omitted from [body] and replaced in each
 * [FrameNote.hex]. Pausing stops new notes from entering [frames]. Export
 * writes every note still in the ring, through a stream the caller opened.
 */
@HiltViewModel
class DebugViewModel @Inject constructor(
    link: StrapLink,
    private val devices: DeviceDirectory,
    val frames: FrameRing,
) : ViewModel() {
    val title: String = "Debug"

    /** Keeps the link and data modules on the debug classpath. */
    val modulePins: String = "${LinkBuildInfo.SPEC}:${DataBuildInfo.SPEC}"

    private val _body = MutableStateFlow(debugBody(link.snapshot(), devices.latest()))
    val body: StateFlow<String> = _body.asStateFlow()

    private val _paused = MutableStateFlow(frames.paused)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    private val _filter = MutableStateFlow(FrameFilter())
    val filter: StateFlow<FrameFilter> = _filter.asStateFlow()

    private val _rows = MutableStateFlow<List<FrameNote>>(emptyList())
    val rows: StateFlow<List<FrameNote>> = _rows.asStateFlow()

    private val _stored = MutableStateFlow(0)
    val stored: StateFlow<Int> = _stored.asStateFlow()

    private val _catalog = MutableStateFlow(FrameCatalog())
    val catalog: StateFlow<FrameCatalog> = _catalog.asStateFlow()

    private val _exportStatus = MutableStateFlow("")
    val exportStatus: StateFlow<String> = _exportStatus.asStateFlow()

    private val unwatchFrames: () -> Unit

    init {
        link.watch { snapshot ->
            _body.value = debugBody(snapshot, devices.latest())
        }
        unwatchFrames = frames.watch { publish() }
        publish()
    }

    fun setPaused(value: Boolean) {
        frames.setPaused(value)
        _paused.value = value
    }

    fun setFilter(value: FrameFilter) {
        _filter.value = value
        publish()
    }

    /** Writes the whole ring, including notes hidden by the current filter. */
    fun export(open: () -> OutputStream?) {
        _exportStatus.value = exportFrames(open, frames.snapshot())
    }

    override fun onCleared() {
        unwatchFrames()
    }

    private fun publish() {
        val all = frames.snapshot()
        _rows.value = selectFrames(all, _filter.value)
        _stored.value = all.size
        _catalog.value = frameCatalog(all)
    }
}
