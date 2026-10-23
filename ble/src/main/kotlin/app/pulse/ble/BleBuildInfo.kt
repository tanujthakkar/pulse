package app.pulse.ble

import app.pulse.wire.ProtocolSpecVersion

/**
 * Transport module marker. Scanning and the serialized GATT session live here.
 * Bonding is a later milestone.
 */
object BleBuildInfo {
    const val SPEC: String = ProtocolSpecVersion.CURRENT
}
