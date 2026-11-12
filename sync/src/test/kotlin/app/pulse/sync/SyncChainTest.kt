package app.pulse.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class SyncChainTest {
    @Test
    fun appStartAndBootDoNotStackASecondChain() {
        val ledger = SyncChainLedger()
        ledger.enqueue(syncChainRequest(SyncChainReason.APP_START))
        ledger.enqueue(syncChainRequest(SyncChainReason.APP_START))
        ledger.enqueue(syncChainRequest(SyncChainReason.BOOT))
        val pending = ledger.pending().single()
        assertEquals(AutoSync.WORK_NAME, pending.name)
        assertEquals(5L * 60L * 1000L, pending.delayMs)
        assertEquals(SyncChainPolicy.KEEP, pending.policy)
        assertEquals(true, pending.requiresBatteryNotLow)
        assertNotEquals(AutoSync.LEGACY_PERIODIC_NAME, pending.name)
    }

    @Test
    fun aFailedRunReschedulesOneRequestFiveMinutesLater() {
        val ledger = SyncChainLedger()
        ledger.enqueue(syncChainRequest(SyncChainReason.APP_START))
        ledger.enqueue(syncChainRequest(SyncChainReason.AFTER_RUN))
        ledger.enqueue(syncChainRequest(SyncChainReason.AFTER_RUN))
        val pending = ledger.pending().single()
        assertEquals(5L * 60L * 1000L, pending.delayMs)
        assertEquals(SyncChainPolicy.REPLACE, pending.policy)
        assertEquals(AutoSync.WORK_NAME, pending.name)
    }
}
