package app.pulse.link

import app.pulse.ble.BleBuildInfo
import app.pulse.wire.ProtocolSpecVersion

/** Link module marker. [StrapLink] is the connect and bond state machine. */
object LinkBuildInfo {
    const val SPEC: String = ProtocolSpecVersion.CURRENT
    const val TRANSPORT_SPEC: String = BleBuildInfo.SPEC
}
