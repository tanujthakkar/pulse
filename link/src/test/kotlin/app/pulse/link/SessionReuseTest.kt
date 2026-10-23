package app.pulse.link

import app.pulse.ble.SessionPhase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SessionReuseTest {
    @Test
    fun anOpeningSessionOnTheSameAddressIsKept() {
        assertEquals(SessionReuse.REUSE, sessionReuse(SessionPhase.OPENING, sameAddress = true))
    }

    @Test
    fun aReadySessionOnTheSameAddressReportsReady() {
        assertEquals(
            SessionReuse.REUSE_AND_READY,
            sessionReuse(SessionPhase.READY, sameAddress = true),
        )
    }

    @Test
    fun aDifferentAddressOrAFinishedSessionIsReplaced() {
        assertEquals(SessionReuse.REPLACE, sessionReuse(null, sameAddress = false))
        assertEquals(SessionReuse.REPLACE, sessionReuse(SessionPhase.OPENING, sameAddress = false))
        assertEquals(SessionReuse.REPLACE, sessionReuse(SessionPhase.READY, sameAddress = false))
        assertEquals(SessionReuse.REPLACE, sessionReuse(SessionPhase.IDLE, sameAddress = true))
        assertEquals(SessionReuse.REPLACE, sessionReuse(SessionPhase.CLOSING, sameAddress = true))
        assertEquals(SessionReuse.REPLACE, sessionReuse(SessionPhase.CLOSED, sameAddress = true))
    }
}
