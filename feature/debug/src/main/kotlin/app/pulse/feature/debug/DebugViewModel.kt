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
import dagger.hilt.android.lifecycle.HiltViewModel
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

/** Shows the link snapshot and the stored device row. Identity text is omitted. */
@HiltViewModel
class DebugViewModel @Inject constructor(
    link: StrapLink,
    private val devices: DeviceDirectory,
) : ViewModel() {
    val title: String = "Debug"

    /** Keeps the link and data modules on the debug classpath. */
    val modulePins: String = "${LinkBuildInfo.SPEC}:${DataBuildInfo.SPEC}"

    private val _body = MutableStateFlow(debugBody(link.snapshot(), devices.latest()))
    val body: StateFlow<String> = _body.asStateFlow()

    init {
        link.watch { snapshot ->
            _body.value = debugBody(snapshot, devices.latest())
        }
    }
}
