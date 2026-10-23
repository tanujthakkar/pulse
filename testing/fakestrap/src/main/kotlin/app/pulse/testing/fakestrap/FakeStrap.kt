package app.pulse.testing.fakestrap

import app.pulse.wire.ProtocolSpecVersion

/**
 * Scripted strap placeholder. Command answers and fault injection are later milestones.
 */
class FakeStrap {
    val specVersion: String = ProtocolSpecVersion.CURRENT
}

/** JVM stand-in for the GATT session. It does not talk to a radio. */
class FakeGattSession
