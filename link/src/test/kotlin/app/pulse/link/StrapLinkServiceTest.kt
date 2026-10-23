package app.pulse.link

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import app.pulse.ble.BondBegin
import app.pulse.ble.CloseReason
import app.pulse.ble.DeviceBond
import app.pulse.ble.StrapSighting
import app.pulse.sync.ChunkReceipt
import app.pulse.sync.R18Draft
import app.pulse.sync.RawFrameDraft
import app.pulse.sync.SyncStore
import app.pulse.wire.codec.DecodeResult
import app.pulse.wire.codec.FrameCodec
import app.pulse.wire.codec.HistoryAckGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowService

/**
 * The history engine is not owned by an Activity.
 *
 * [HistorySyncHost] creates and retains [app.pulse.sync.HistorySyncDriver].
 * [StrapLinkService] holds that host in [StrapLinkService.retainedSync] for
 * the life of the service. Pause and stop are Activity callbacks. Nothing in
 * the host, the driver, or the service observes them, so an in-flight sync
 * keeps running while the service is up. Disconnecting the strap (the link
 * returns to idle) stops the service and removes the notification.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StrapLinkServiceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var controller: ServiceController<StrapLinkService>? = null

    @After
    fun tearDown() {
        LinkServiceHooks.deps = null
        controller?.destroy()
        controller = null
    }

    @Test
    fun linkedStrapPostsAForegroundNotificationAndDisconnectClearsIt() {
        val fx = linked()
        val service = start(fx)
        val shadow = shadowOf(service)
        val notification = shadow.lastForegroundNotification
        assertNotNull(notification)
        assertEquals(LinkNotifications.NOTE_ID, shadow.lastForegroundNotificationId)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, foregroundType(shadow))
        assertEquals(LinkNotifications.TITLE, notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertEquals("Strap connected · 80% battery", text)
        assertFalse(text.contains("WHOOP"))
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertFalse(shadow.isStoppedBySelf)

        fx.link.onSessionClosed(CloseReason.LINK_LOST, status = 0)
        assertEquals(LinkPhase.SCANNING, fx.link.snapshot().phase)
        assertFalse(shadow.isStoppedBySelf)
        assertEquals(
            "Looking for a strap",
            shadow.lastForegroundNotification.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )

        fx.link.stop()
        assertEquals(LinkPhase.IDLE, fx.link.snapshot().phase)
        assertTrue(shadow.isStoppedBySelf)
        assertTrue(shadow.isForegroundStopped)
        val manager = service.getSystemService(NotificationManager::class.java)
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test
    @Config(sdk = [33])
    fun olderPlatformsStillPostTheConnectedNotification() {
        val fx = linked()
        val service = start(fx)
        val shadow = shadowOf(service)
        val text = shadow.lastForegroundNotification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertEquals("Strap connected · 80% battery", text)
        assertEquals(0, foregroundType(shadow))
        fx.link.stop()
        assertTrue(shadow.isForegroundStopped)
    }

    @Test
    fun activityPauseAndStopDoesNotCancelTheSyncTheServiceOwns() {
        val fx = linked()
        val service = start(fx)
        fx.commands.markReady(ADDRESS)
        fx.sync.syncNow()
        assertTrue(fx.sync.running.value)
        assertEquals("Syncing", fx.sync.status.value)
        assertSame(fx.sync, service.retainedSync)
        assertFalse(HistoryAckGate.maySend())
        assertFalse(HistoryAckGate.OFFSETS_CONFIRMED)
        assertTrue(fx.written.isNotEmpty())
        fx.written.forEach { frame ->
            val decoded = FrameCodec.decode(frame)
            assertTrue(decoded is DecodeResult.Ok)
            assertTrue((decoded as DecodeResult.Ok).frame.command != HistoryAckGate.NUMBER)
        }
        val text = shadowOf(service).lastForegroundNotification.extras
            .getCharSequence(Notification.EXTRA_TEXT)
            .toString()
        assertTrue(text.startsWith("Syncing strap history"))

        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        activity.pause().stop()
        assertTrue(fx.sync.running.value)
        assertEquals("Syncing", fx.sync.status.value)
        assertSame(fx.sync, service.retainedSync)
        assertFalse(shadowOf(service).isStoppedBySelf)

        activity.destroy()
        assertTrue(fx.sync.running.value)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    private fun start(fx: Fixture): StrapLinkService {
        LinkServiceHooks.deps = LinkServiceDeps(
            link = fx.link,
            sync = fx.sync,
            notifications = LinkNotifications(context),
        )
        val created = Robolectric.buildService(StrapLinkService::class.java).create()
        controller = created
        return created.startCommand(0, 0).get()
    }

    private fun linked(): Fixture {
        val commands = StrapCommandPort()
        val written = mutableListOf<ByteArray>()
        commands.bind { frame -> written += frame.copyOf() }
        val link = StrapLink(presentPorts(), idleClock())
        link.start()
        link.onSighting(StrapSighting(ADDRESS, rssi = -40, advertisedName = null))
        link.onSessionReady()
        link.onDeviceReport(
            DeviceReport(
                settled = true,
                succeeded = true,
                serviceBatteryPercent = 80,
                heartRateLive = true,
            ),
        )
        assertEquals(LinkPhase.LINKED, link.snapshot().phase)
        val sync = HistorySyncHost(commands, UnusedSyncStore())
        return Fixture(link, commands, sync, written)
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

    private class Fixture(
        val link: StrapLink,
        val commands: StrapCommandPort,
        val sync: HistorySyncHost,
        val written: MutableList<ByteArray>,
    )

    private class UnusedSyncStore : SyncStore {
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

        override fun closeSession(
            sessionId: Long,
            endedUnixSeconds: Long,
            endedSubsecondTicks: Int?,
        ) = error("not used")

        override fun markAcknowledged(
            sessionId: Long,
            token: ByteArray,
            ackedUnixSeconds: Long,
        ) = error("not used")
    }

    private fun foregroundType(shadow: ShadowService): Int {
        val method = ShadowService::class.java.getDeclaredMethod("getForegroundServiceType")
        method.isAccessible = true
        return method.invoke(shadow) as Int
    }

    private companion object {
        const val ADDRESS: String = "AA:BB:CC:DD:EE:FF"
    }
}
