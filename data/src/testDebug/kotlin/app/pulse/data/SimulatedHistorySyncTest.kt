package app.pulse.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.pulse.ble.SimulatedPull
import app.pulse.ble.SimulatedStrap
import app.pulse.ble.SimulatedStrapScript
import app.pulse.ble.StrapScenario
import app.pulse.sync.HistorySyncEngine
import app.pulse.sync.PhoneClock
import app.pulse.sync.SessionOutcome
import app.pulse.sync.phoneTimeFromUnixMillis
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The normal simulated strap, through [HistorySyncEngine] into pulse-sim.db.
 *
 * Hello and battery are the link handshake. This is the history attempt that
 * follows them: clock, data range, history start, then the same chunked
 * notifications the debug radio pushes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SimulatedHistorySyncTest {
    private lateinit var database: PulseDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(SimulatedStrap.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(SimulatedStrap.KEY_ENABLED, true)
            .commit()
        assertEquals(PulseStoreFiles.SIMULATED, PulseStoreFiles.name(context))
        context.deleteDatabase(PulseStoreFiles.SIMULATED)
        database = Room.databaseBuilder(context, PulseDatabase::class.java, PulseStoreFiles.name(context))
            .allowMainThreadQueries()
            .build()
        database.devices().upsert(
            DeviceRow(
                address = SimulatedStrap.ADDRESS,
                helloFirmware = "50.43.1.0",
                informationFirmware = null,
                commandBatteryPercent = 76,
                serviceBatteryPercent = 76,
                updatedAtMillis = 1,
            ),
        )
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
    }

    @Test
    fun normalScenarioFillsSeveralDaysInTheSimDatabase() {
        val strap = SimulatedStrapScript(
            scenario = StrapScenario.NORMAL,
            today = LocalDate.of(2026, 11, 8),
            zone = ZoneOffset.UTC,
            clockSeconds = { STRAP_UNIX },
        )
        val store = RecordingStore(RoomSyncStore(database))
        val engine = HistorySyncEngine(
            address = SimulatedStrap.ADDRESS,
            store = store,
            phone = PhoneClock { phoneTimeFromUnixMillis(STRAP_UNIX * 1000L) },
            transmit = { frame ->
                val replies = strap.onCommand(frame)
                val armed = strap.consumeArm()
                replies.forEach { engineNotification(it) }
                if (armed) pump(strap)
                true
            },
            historyAckEnabled = { false },
            nowMs = { 0L },
        )
        engineRef = engine
        engine.start()
        val trace = engine.events.joinToString(" ") { event ->
            when (event) {
                is app.pulse.sync.SyncTrace.Sent -> "sent:${event.command}"
                is app.pulse.sync.SyncTrace.Opened -> "opened"
                is app.pulse.sync.SyncTrace.Commit -> "commit:${event.samples}"
                is app.pulse.sync.SyncTrace.Closed -> "closed:${event.outcome}"
                else -> event.javaClass.simpleName
            }
        }
        assertEquals(
            "$trace ${store.failure?.javaClass?.simpleName}: ${store.failure?.message}",
            SessionOutcome.COMPLETE,
            engine.outcome,
        )
        assertTrue(engine.snapshot().records > 10_000)
        val book = RoomDayMetrics(database, RoomDeviceDirectory(database))
            .snapshot(ZoneOffset.UTC)
            .book
        assertNull(book.emptyReason)
        assertTrue(book.days.size >= 3)
        assertTrue(book.days.count { it.main != null } >= 2)
        assertTrue(book.days.any { it.stepIncrease > 0 })
        assertTrue(book.days.any { it.rhythm?.restingHr != null })
        val today = book.days.first { it.date == LocalDate.of(2026, 11, 8) }
        assertTrue(today.main != null)
        assertTrue((today.recovery?.baselineDays ?: 0) >= 3)
        assertTrue(today.recovery?.score != null)
    }

    private var engineRef: HistorySyncEngine? = null

    private fun engineNotification(frame: ByteArray) {
        engineRef?.onNotification("sim-data", frame)
    }

    private fun pump(strap: SimulatedStrapScript) {
        while (true) {
            when (val pull = strap.pull(400)) {
                is SimulatedPull.Done -> return
                is SimulatedPull.Disconnect -> {
                    pull.frames.forEach(::engineNotification)
                    return
                }
                is SimulatedPull.Frames -> pull.frames.forEach(::engineNotification)
            }
        }
    }

    private class RecordingStore(private val real: RoomSyncStore) : app.pulse.sync.SyncStore {
        var failure: Throwable? = null

        override fun openSession(
            deviceAddress: String,
            startedUnixSeconds: Long,
            startedSubsecondTicks: Int?,
            rangeSnapshot: ByteArray,
        ): Long = guard { real.openSession(deviceAddress, startedUnixSeconds, startedSubsecondTicks, rangeSnapshot) }

        override fun commitChunk(
            sessionId: Long,
            frames: List<app.pulse.sync.RawFrameDraft>,
            r18Rows: List<app.pulse.sync.R18Draft>,
            archived: List<app.pulse.sync.RawFrameDraft>,
            token: ByteArray,
        ) = guard { real.commitChunk(sessionId, frames, r18Rows, archived, token) }

        override fun closeSession(sessionId: Long, endedUnixSeconds: Long, endedSubsecondTicks: Int?) =
            guard { real.closeSession(sessionId, endedUnixSeconds, endedSubsecondTicks) }

        override fun markAcknowledged(sessionId: Long, token: ByteArray, ackedUnixSeconds: Long) =
            guard { real.markAcknowledged(sessionId, token, ackedUnixSeconds) }

        override fun savedCounts() = real.savedCounts()

        override fun persistedChunk(sessionId: Long, token: ByteArray) = real.persistedChunk(sessionId, token)

        private inline fun <T> guard(block: () -> T): T = try {
            block()
        } catch (error: Throwable) {
            failure = error
            throw error
        }
    }

    private companion object {
        const val STRAP_UNIX: Long = 1_762_560_000L
    }
}
