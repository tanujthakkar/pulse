package app.pulse.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AutoSyncTest {
    @Test
    fun pullsOnConnectThenOnTheIntervalAndOnForeground() {
        assertEquals(20L * 60L * 1000L, AutoSync.INTERVAL_MS)
        assertEquals(AutoSyncAction.SYNC, autoSyncAction(input(AutoSyncTrigger.CONNECTED, linked = true)))
        assertEquals(
            AutoSyncAction.NONE,
            autoSyncAction(input(AutoSyncTrigger.CONNECTED, linked = true, running = true)),
        )
        assertEquals(
            AutoSyncAction.SYNC,
            autoSyncAction(input(AutoSyncTrigger.FOREGROUND, linked = true, elapsed = null)),
        )
        assertEquals(
            AutoSyncAction.NONE,
            autoSyncAction(input(AutoSyncTrigger.FOREGROUND, linked = true, elapsed = 1_000L)),
        )
        assertEquals(
            AutoSyncAction.SYNC,
            autoSyncAction(
                input(AutoSyncTrigger.FOREGROUND, linked = true, elapsed = AutoSync.FOREGROUND_COOLDOWN_MS),
            ),
        )
        assertEquals(
            AutoSyncAction.NONE,
            autoSyncAction(input(AutoSyncTrigger.PERIODIC, linked = true, elapsed = AutoSync.INTERVAL_MS - 1)),
        )
        assertEquals(
            AutoSyncAction.SYNC,
            autoSyncAction(input(AutoSyncTrigger.PERIODIC, linked = true, elapsed = AutoSync.INTERVAL_MS)),
        )
        assertEquals(
            AutoSyncAction.NONE,
            autoSyncAction(input(AutoSyncTrigger.WORKER, linked = true, elapsed = 1_000L)),
        )
        assertEquals(
            AutoSyncAction.SYNC,
            autoSyncAction(input(AutoSyncTrigger.WORKER, linked = true, elapsed = AutoSync.INTERVAL_MS)),
        )
    }

    @Test
    fun aDroppedLinkReconnectsOnlyFromTheWorkerOrForeground() {
        assertEquals(
            AutoSyncAction.RECONNECT,
            autoSyncAction(input(AutoSyncTrigger.WORKER, linked = false, idle = true, known = true)),
        )
        assertEquals(
            AutoSyncAction.RECONNECT,
            autoSyncAction(input(AutoSyncTrigger.FOREGROUND, linked = false, failed = true, known = true)),
        )
        assertEquals(
            AutoSyncAction.NONE,
            autoSyncAction(input(AutoSyncTrigger.WORKER, linked = false, idle = true, known = false)),
        )
        assertEquals(
            AutoSyncAction.NONE,
            autoSyncAction(input(AutoSyncTrigger.PERIODIC, linked = false, idle = true, known = true)),
        )
        assertEquals(
            AutoSyncAction.NONE,
            autoSyncAction(input(AutoSyncTrigger.WORKER, linked = false, known = true)),
        )
        assertEquals(
            AutoSyncAction.NONE,
            autoSyncAction(input(AutoSyncTrigger.WORKER, linked = false, idle = true, known = true, running = true)),
        )
    }

    private fun input(
        trigger: AutoSyncTrigger,
        linked: Boolean,
        running: Boolean = false,
        elapsed: Long? = null,
        idle: Boolean = false,
        failed: Boolean = false,
        known: Boolean = true,
    ) = AutoSyncInput(
        trigger = trigger,
        linked = linked,
        running = running,
        millisSinceLastStart = elapsed,
        phaseIdle = idle,
        phaseFailed = failed,
        knownDevice = known,
    )
}
