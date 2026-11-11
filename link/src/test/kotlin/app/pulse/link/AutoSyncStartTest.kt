package app.pulse.link

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import app.pulse.ble.BondBegin
import app.pulse.ble.DeviceBond
import app.pulse.ble.StrapSighting
import app.pulse.data.DeviceDirectory
import app.pulse.data.DeviceRow
import app.pulse.sync.AutoSyncOutcome
import app.pulse.sync.AutoSyncTrigger
import app.pulse.sync.ChunkReceipt
import app.pulse.sync.PersistedChunk
import app.pulse.sync.R18Draft
import app.pulse.sync.RawFrameDraft
import app.pulse.sync.SavedCounts
import app.pulse.sync.SyncStore
import app.pulse.wire.codec.ClockCommand
import app.pulse.wire.codec.DecodeResult
import app.pulse.wire.codec.FrameCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Auto-sync used to mark a start when the link became [LinkPhase.LINKED],
 * before hello had given the command port an address. [syncNow] wrote nothing,
 * the cooldown began, and [AutoSyncWorker] still returned success.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoSyncStartTest {
    @Test
    fun workerRetriesWhenTheStrapIsNotReadyAndSucceedsOnceASyncStarts() {
        assertEquals(ListenableWorker.Result.retry(), autoSyncWorkerResult(AutoSyncOutcome.NOT_READY))
        assertEquals(ListenableWorker.Result.retry(), autoSyncWorkerResult(AutoSyncOutcome.RECONNECTING))
        assertEquals(ListenableWorker.Result.success(), autoSyncWorkerResult(AutoSyncOutcome.STARTED))
        assertEquals(ListenableWorker.Result.success(), autoSyncWorkerResult(AutoSyncOutcome.IDLE))
    }

    @Test
    fun watchStartsHistoryOnceHelloSucceedsAfterTheCommandPortIsReady() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val commands = StrapCommandPort()
        val written = mutableListOf<ByteArray>()
        commands.bind { frame -> written += frame.copyOf() }
        commands.markReady(ADDRESS)
        val link = StrapLink(presentPorts(), idleClock())
        val sync = HistorySyncHost(commands, QuietStore(), link)
        val coordinator = AutoSyncCoordinator(link, sync, NoDevice(), context)
        coordinator.armLinkWatch()

        link.start()
        link.onSighting(StrapSighting(ADDRESS, rssi = -40, advertisedName = null))
        link.onSessionReady()
        assertFalse(sync.running.value)
        assertTrue(written.isEmpty())

        link.onDeviceReport(DeviceReport(settled = true, succeeded = true, heartRateLive = true))
        assertTrue(sync.running.value)
        val frame = FrameCodec.decode(written.single()) as DecodeResult.Ok
        assertEquals(ClockCommand.GET, frame.frame.command)
    }

    @Test
    fun linkedBeforeHelloDoesNotCountAsASyncAndHelloDoes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val commands = StrapCommandPort()
        val written = mutableListOf<ByteArray>()
        commands.bind { frame -> written += frame.copyOf() }
        val link = StrapLink(presentPorts(), idleClock())
        val sync = HistorySyncHost(commands, QuietStore(), link)
        val coordinator = AutoSyncCoordinator(link, sync, NoDevice(), context)
        coordinator.armLinkWatch()

        link.start()
        link.onSighting(StrapSighting(ADDRESS, rssi = -40, advertisedName = null))
        link.onSessionReady()
        assertEquals(LinkPhase.LINKED, link.snapshot().phase)
        assertEquals(HelloProgress.WAITING, link.snapshot().hello)
        assertFalse(sync.running.value)
        assertTrue(written.isEmpty())
        assertEquals(AutoSyncOutcome.IDLE, coordinator.on(AutoSyncTrigger.FOREGROUND))

        link.onDeviceReport(DeviceReport(settled = true, succeeded = true, heartRateLive = true))
        assertEquals(HelloProgress.SUCCEEDED, link.snapshot().hello)
        assertEquals(AutoSyncOutcome.NOT_READY, coordinator.on(AutoSyncTrigger.FOREGROUND))
        assertFalse(sync.running.value)
        assertEquals(AutoSyncOutcome.NOT_READY, coordinator.on(AutoSyncTrigger.WORKER))

        commands.markReady(ADDRESS)
        assertEquals(AutoSyncOutcome.STARTED, coordinator.on(AutoSyncTrigger.CONNECTED))
        assertTrue(sync.running.value)
        val frame = FrameCodec.decode(written.single()) as DecodeResult.Ok
        assertEquals(ClockCommand.GET, frame.frame.command)
        assertEquals(AutoSyncOutcome.IN_PROGRESS, coordinator.on(AutoSyncTrigger.WORKER))
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
        const val ADDRESS: String = "AA:BB:CC:DD:EE:FF"
    }
}
