package app.pulse.testing.fakestrap

import app.pulse.wire.ProtocolSpecVersion

/**
 * Scripted strap placeholder. Command answers and fault injection are later milestones.
 */
class FakeStrap {
    val specVersion: String = ProtocolSpecVersion.CURRENT
}

/**
 * JVM stand-in reserved for later scripted strap tests.
 *
 * It does not talk to a radio. The serialized GATT session is tested in `:ble`
 * with an in-memory radio, because this module cannot depend on Android.
 */
class FakeGattSession
