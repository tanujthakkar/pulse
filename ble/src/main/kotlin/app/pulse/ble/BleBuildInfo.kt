package app.pulse.ble

import app.pulse.wire.ProtocolSpecVersion

/**
 * Transport module marker. Scanning, the GATT queue, and bonding are later milestones.
 */
object BleBuildInfo {
    const val SPEC: String = ProtocolSpecVersion.CURRENT
}
