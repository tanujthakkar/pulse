package app.pulse.link

import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.pulse.ble.StrapGatt
import app.pulse.data.PulseDatabase
import app.pulse.data.RoomDeviceDirectory
import app.pulse.wire.codec.CommandResult
import app.pulse.wire.codec.FrameCodec
import app.pulse.wire.codec.HelloBody
import app.pulse.wire.codec.HelloCommand
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The simulator delivers hello on the main thread. Room rejects that thread.
 * These tests use a real database with the main-thread guard left on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HandshakeRoomTest {
    private val database: PulseDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        PulseDatabase::class.java,
    ).build()
    private val devices = RoomDeviceDirectory(database)

    @After
    fun close() {
        database.close()
    }

    @Test
    fun helloOnTheMainThreadSavesTheDeviceRow() {
        assertSame(Looper.getMainLooper(), Looper.myLooper())
        val reports = mutableListOf<DeviceReport>()
        val handshake = handshake(reports)
        handshake.start()
        handshake.onRead(
            StrapGatt.FIRMWARE_REVISION,
            status = 0,
            value = "50.43.1.0".encodeToByteArray(),
        )
        handshake.onNotification(StrapGatt.RESPONSE_NOTIFY, helloFrame())
        val settled = reports.last { it.settled }
        assertTrue(settled.succeeded)
        val stored = devices.latest()
        assertEquals("AA:BB:CC:DD:EE:FF", stored?.address)
        assertEquals("50.43.1.0", stored?.helloFirmware)
        assertEquals("50.43.1.0", stored?.informationFirmware)
    }

    @Test
    fun helloQueuedOffTheCallerStillSaves() {
        assertSame(Looper.getMainLooper(), Looper.myLooper())
        val saved = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "pulse-handshake-test").apply { isDaemon = true }
        }
        val queued = StrapHandshake(
            address = "AA:BB:CC:DD:EE:FF",
            knows = { true },
            write = {},
            read = {},
            clock = handshakeClock(),
            devices = devices,
            heartRateLive = { false },
            report = { report ->
                if (report.settled && report.succeeded) saved.countDown()
            },
            runner = HandshakeRunner(executor),
        )
        queued.start()
        queued.onNotification(StrapGatt.RESPONSE_NOTIFY, helloFrame())
        assertTrue(saved.await(5, TimeUnit.SECONDS))
        assertEquals("50.43.1.0", devices.latest()?.helloFirmware)
        executor.shutdown()
    }

    private fun handshake(reports: MutableList<DeviceReport>): StrapHandshake {
        return StrapHandshake(
            address = "AA:BB:CC:DD:EE:FF",
            knows = { true },
            write = {},
            read = {},
            clock = handshakeClock(),
            devices = devices,
            heartRateLive = { false },
            report = { reports += it },
        )
    }

    private fun handshakeClock(): LinkClock = object : LinkClock {
        override fun after(delayMs: Long, task: () -> Unit): LinkAlarm = LinkAlarm {}
    }

    private fun helloFrame(): ByteArray {
        val body = ByteArray(HelloBody.FINAL_LENGTH)
        body[0] = HelloCommand.REVISION.toByte()
        body[HelloBody.VERSION_OFFSET] = 50.toByte()
        body[HelloBody.VERSION_OFFSET + 1] = 43.toByte()
        body[HelloBody.VERSION_OFFSET + 2] = 1.toByte()
        return FrameCodec.encodeCommandResponse(
            sequence = 9,
            command = HelloCommand.NUMBER,
            originSequence = HelloCommand.DOCUMENTED_SEQUENCE,
            result = CommandResult.SUCCESS,
            body = body,
        )
    }
}
