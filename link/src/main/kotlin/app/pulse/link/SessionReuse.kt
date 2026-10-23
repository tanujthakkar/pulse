package app.pulse.link

import app.pulse.ble.SessionPhase

/**
 * Whether the GATT client opened for heart rate should stay up when the
 * link is ready to treat the session as the connected one.
 */
internal enum class SessionReuse {
    REUSE,
    REUSE_AND_READY,
    REPLACE,
}

internal fun sessionReuse(phase: SessionPhase?, sameAddress: Boolean): SessionReuse {
    if (!sameAddress || phase == null) return SessionReuse.REPLACE
    return when (phase) {
        SessionPhase.OPENING -> SessionReuse.REUSE
        SessionPhase.READY -> SessionReuse.REUSE_AND_READY
        SessionPhase.IDLE,
        SessionPhase.CLOSING,
        SessionPhase.CLOSED,
        -> SessionReuse.REPLACE
    }
}
