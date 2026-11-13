package app.pulse.link

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import app.pulse.ble.BondBegin
import app.pulse.ble.DeviceBond
import app.pulse.ble.StrapSighting
import app.pulse.data.DeviceDirectory
import app.pulse.data.DeviceRow
import app.pulse.sync.AutoSync
import app.pulse.sync.AutoSyncOutcome
import app.pulse.sync.AutoSyncTrigger
import app.pulse.sync.ChunkReceipt
import app.pulse.sync.PersistedChunk
import app.pulse.sync.R18Draft
import app.pulse.sync.RawFrameDraft
import app.pulse.sync.SavedCounts
import app.pulse.sync.SyncChainReason
import app.pulse.sync.SyncStore
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The launch pull and the chain used to start together. The worker then fired
 * during the cooldown, returned failure, and APPEND_OR_REPLACE marked the
 * follow-up FAILED with no enqueue time. These tests run that sequence on
 * WorkManager and require a live ENQUEUED request afterward.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncChainWorkTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        if (!initialized) {
            val workers = Executors.newSingleThreadExecutor()
            val tasks = Executors.newSingleThreadExecutor()
            val config = Configuration.Builder()
                .setExecutor(workers)
                .setTaskExecutor(tasks)
                .setMinimumLoggingLevel(Log.DEBUG)
                .build()
            WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
            initialized = true
        }
        WorkManager.getInstance(context).cancelAllWork().result.get(5, TimeUnit.SECONDS)
        AutoSyncWorker.coordinatorForTest = null
    }

    @After
    fun tearDown() {
        AutoSyncWorker.coordinatorForTest = null
    }

    @Test
    fun aCooldownAfterLaunchStillLeavesAnEnqueuedFollowUp() {
        val coordinator = readyAfterLaunchSync()
        AutoSyncWorker.coordinatorForTest = coordinator
        val first = enqueueChain()
        drive(first)
        assertEquals(1, coordinator.workerRuns)
        assertEquals(AutoSyncOutcome.COOLING_DOWN, coordinator.lastWorkerOutcome)
        val followUp = awaitEnqueued(excluding = first)
        assertEquals(WorkInfo.State.ENQUEUED, followUp.state)
        assertNotEquals(first, followUp.id)
        assertNoFailures()
    }

    @Test
    fun twoConsecutiveCyclesEachLeaveAnEnqueuedFollowUp() {
        val coordinator = readyAfterLaunchSync()
        AutoSyncWorker.coordinatorForTest = coordinator
        val first = enqueueChain()
        drive(first)
        val second = awaitEnqueued(excluding = first)
        drive(second.id)
        assertEquals(2, coordinator.workerRuns)
        assertEquals(AutoSyncOutcome.COOLING_DOWN, coordinator.lastWorkerOutcome)
        val third = awaitEnqueued(excluding = second.id)
        assertEquals(WorkInfo.State.ENQUEUED, third.state)
        assertNoFailures()
    }

    @Test
    fun intervalTicksLeaveTwoWorkerRunsSucceeded() {
        val coordinator = readyAfterLaunchSync()
        val host = sync
        AutoSyncWorker.coordinatorForTest = coordinator
        val first = enqueueChain()
        pokeInterval(coordinator, host)
        assertEquals(first, awaitEnqueued(excluding = null).id)
        drive(first)
        assertEquals(WorkInfo.State.SUCCEEDED, info(first).state)
        val second = awaitEnqueued(excluding = first)
        pokeInterval(coordinator, host)
        assertEquals(second.id, awaitEnqueued(excluding = first).id)
        drive(second.id)
        assertEquals(WorkInfo.State.SUCCEEDED, info(second.id).state)
        assertEquals(2, coordinator.workerRuns)
        assertEquals(AutoSyncOutcome.COOLING_DOWN, coordinator.lastWorkerOutcome)
        assertEquals(WorkInfo.State.ENQUEUED, awaitEnqueued(excluding = second.id).state)
        assertNoFailures()
    }

    private lateinit var sync: HistorySyncHost

    private fun pokeInterval(coordinator: AutoSyncCoordinator, host: HistorySyncHost) {
        coordinator.rememberFinish(System.currentTimeMillis() - AutoSync.INTERVAL_MS)
        coordinator.onInterval()
        assertEquals(AutoSyncOutcome.IDLE, coordinator.on(AutoSyncTrigger.PERIODIC))
        assertFalse(host.running.value)
        coordinator.rememberFinish(System.currentTimeMillis())
    }

    private fun readyAfterLaunchSync(): AutoSyncCoordinator {
        val commands = StrapCommandPort()
        commands.bind { }
        commands.markReady(ADDRESS)
        val link = StrapLink(presentPorts(), idleClock())
        val host = HistorySyncHost(commands, QuietStore(), link)
        sync = host
        val coordinator = AutoSyncCoordinator(link, host, NoDevice(), context)
        link.start()
        link.onSighting(StrapSighting(ADDRESS, rssi = -40, advertisedName = null))
        link.onSessionReady()
        link.onDeviceReport(DeviceReport(settled = true, succeeded = true, heartRateLive = true))
        assertEquals(HelloProgress.SUCCEEDED, link.snapshot().hello)
        assertEquals(false, host.running.value)
        coordinator.rememberFinish(System.currentTimeMillis())
        return coordinator
    }

    private fun enqueueChain(): UUID {
        AutoSyncWorker.coordinatorForTest!!.ensureChain(SyncChainReason.APP_START)
        return awaitEnqueued(excluding = null).id
    }

    private fun info(id: UUID): WorkInfo =
        WorkManager.getInstance(context).getWorkInfoById(id).get(2, TimeUnit.SECONDS)!!

    private fun drive(id: UUID) {
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        driver.setAllConstraintsMet(id)
        driver.setInitialDelayMet(id)
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline) {
            val info = WorkManager.getInstance(context).getWorkInfoById(id).get(2, TimeUnit.SECONDS)
            if (info != null && info.state.isFinished) return
            Thread.sleep(25)
        }
        error("work $id did not finish")
    }

    private fun awaitEnqueued(excluding: UUID?): WorkInfo {
        val deadline = System.currentTimeMillis() + 8_000
        var last = ""
        while (System.currentTimeMillis() < deadline) {
            val infos = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(AutoSync.WORK_NAME)
                .get(2, TimeUnit.SECONDS)
            last = infos.joinToString { "${it.id} ${it.state}" }
            val live = infos.firstOrNull { it.state == WorkInfo.State.ENQUEUED && it.id != excluding }
            if (live != null) return live
            Thread.sleep(25)
        }
        error("no enqueued follow-up; saw $last")
    }

    private fun assertNoFailures() {
        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(AutoSync.WORK_NAME)
            .get(2, TimeUnit.SECONDS)
        assertTrue(infos.none { it.state == WorkInfo.State.FAILED })
    }

    private fun presentPorts(): LinkPorts = object : LinkPorts {
        override fun startScan() = Unit
        override fun stopScan() = Unit
        override fun bondOf(address: String): DeviceBond = DeviceBond.PRESENT
        override fun beginBond(address: String): BondBegin = BondBegin.STARTED
        override fun cancelBond() = Unit
        override fun openSession(address: String) = Unit
        override fun closeSession() = Unit
        override fun watchHeartRate(address: String) = Unit
    }

    private fun idleClock(): LinkClock = object : LinkClock {
        override fun after(delayMs: Long, task: () -> Unit): LinkAlarm = LinkAlarm { }
    }

    private class NoDevice : DeviceDirectory {
        override fun save(row: DeviceRow) = Unit
        override fun latest(): DeviceRow? = null
    }

    private class QuietStore : SyncStore {
        override fun openSession(
            deviceAddress: String,
            startedUnixSeconds: Long,
            startedSubsecondTicks: Int?,
            rangeSnapshot: ByteArray,
        ): Long = error("not used")

        override fun commitChunk(
            sessionId: Long,
            frames: List<RawFrameDraft>,
            r18Rows: List<R18Draft>,
            archived: List<RawFrameDraft>,
            token: ByteArray,
        ): ChunkReceipt = error("not used")

        override fun closeSession(sessionId: Long, endedUnixSeconds: Long, endedSubsecondTicks: Int?) =
            error("not used")

        override fun markAcknowledged(sessionId: Long, token: ByteArray, ackedUnixSeconds: Long) =
            error("not used")

        override fun savedCounts(): SavedCounts = SavedCounts(0, 0)

        override fun persistedChunk(sessionId: Long, token: ByteArray): PersistedChunk? = null
    }

    private companion object {
        var initialized = false
        const val ADDRESS: String = "AA:BB:CC:DD:EE:FF"
    }
}
