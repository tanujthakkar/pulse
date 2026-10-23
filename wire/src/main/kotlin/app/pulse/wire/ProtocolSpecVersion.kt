package app.pulse.wire

/**
 * Revision of docs/protocol-spec.md this tree is aligned with.
 *
 * The frame codec is a later milestone. This module stays a pure JVM library
 * and must not gain Android dependencies or an implementation ahead of that work.
 */
object ProtocolSpecVersion {
    const val CURRENT: String = "0"
}
