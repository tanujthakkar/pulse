package app.pulse.ble

import app.pulse.wire.ProtocolSpecVersion

/**
 * Transport module marker. Scanning, the serialized GATT session, and the
 * bond port live here. The connect/bond state machine lives in `:link`.
 */
object BleBuildInfo {
    const val SPEC: String = ProtocolSpecVersion.CURRENT
}
