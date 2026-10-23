package app.pulse.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import app.pulse.sync.R18Draft
import app.pulse.sync.RawFrameDraft
import app.pulse.sync.SyncClock
import app.pulse.wire.codec.HistoryAckGate
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RoomSyncStoreTest {
    private lateinit var database: PulseDatabase
    private lateinit var reads: ExecutorService
    private val clock = SyncClock { STORED_AT }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PulseDatabase::class.java).build()
        reads = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "pulse-sync-read").apply { isDaemon = true }
        }
        read {
            database.devices().upsert(
                DeviceRow(
                    address = ADDRESS,
                    helloFirmware = "50.42.1.0",
                    informationFirmware = null,
                    commandBatteryPercent = 80,
                    serviceBatteryPercent = 81,
                    updatedAtMillis = 11,
                ),
            )
        }
    }

    @After
    fun tearDown() {
        database.close()
        reads.shutdown()
    }

    @Test
    fun failureMidTransactionLeavesNoRowsFromThatChunk() {
        val store = RoomSyncStore(database, clock = clock)
        val session = store.openSession(ADDRESS, STARTED_AT, 32)
        val prior = store.commitChunk(
            sessionId = session,
            frames = listOf(RawFrameDraft(byteArrayOf(1, 2, 3), packetType = 47)),
            r18Rows = listOf(sample(recordIndex = 1, heartRateRaw = 60)),
            archived = emptyList(),
            token = token(1),
        )
        assertFalse(prior.duplicate)
        assertEquals(1, prior.framesInserted)

        var sawSyncThread = false
        val failing = RoomSyncStore(
            database,
            clock = clock,
            afterFramesInserted = {
                sawSyncThread = Thread.currentThread().name == "pulse-sync"
                throw IllegalStateException("injected")
            },
        )
        val error = assertThrows(IllegalStateException::class.java) {
            failing.commitChunk(
                sessionId = session,
                frames = listOf(RawFrameDraft(byteArrayOf(9), packetType = 47)),
                r18Rows = listOf(sample(recordIndex = 2, heartRateRaw = 61)),
                archived = listOf(RawFrameDraft(byteArrayOf(8), layout = 20)),
                token = token(2),
            )
        }
        assertEquals("injected", error.message)
        assertTrue(sawSyncThread)
        assertEquals(1, read { database.sessions().count() })
        assertEquals(1, read { database.chunks().count() })
        assertEquals(1, read { database.frames().count() })
        assertEquals(1, read { database.samples().count() })
        assertNull(read { database.chunks().find(ADDRESS, token(2)) })
        assertEquals(60, read { database.samples().find(ADDRESS, 1, SAMPLE_UNIX) }?.heartRateRaw)
        assertNull(read { database.chunks().find(ADDRESS, token(1)) }?.ackedAt)
        assertFalse(HistoryAckGate.maySend())
        assertEquals(ADDRESS, read { database.devices().latest() }?.address)
    }

    @Test
    fun duplicateTokenAndRepeatedRowsAreNoOps() {
        val store = RoomSyncStore(database, clock = clock)
        val session = store.openSession(ADDRESS, STARTED_AT, null)
        var threadName = ""
        val recording = RoomSyncStore(
            database,
            clock = clock,
            afterFramesInserted = { threadName = Thread.currentThread().name },
        )
        val frame = RawFrameDraft(
            bytes = byteArrayOf(4, 5),
            packetType = 47,
            layout = 18,
            recordIndex = 7,
            unixSeconds = 100,
            subsecondTicks = 4,
        )
        val archived = RawFrameDraft(bytes = byteArrayOf(6), layout = 21, unixSeconds = 100)
        val row = sample(recordIndex = 7, heartRateRaw = 70, heartRateBpm = 70)
        val first = recording.commitChunk(
            sessionId = session,
            frames = listOf(frame, frame),
            r18Rows = listOf(row),
            archived = listOf(archived),
            token = token(3),
        )
        assertEquals("pulse-sync", threadName)
        assertFalse(first.duplicate)
        assertEquals(1, first.framesInserted)
        assertEquals(1, first.samplesInserted)
        assertEquals(1, first.archivedInserted)

        val second = store.commitChunk(
            sessionId = session,
            frames = listOf(frame),
            r18Rows = listOf(row.copy(heartRateRaw = 99, heartRateBpm = 99)),
            archived = listOf(archived),
            token = token(3),
        )
        assertTrue(second.duplicate)
        assertEquals(0, second.framesInserted)
        assertEquals(first.chunkId, second.chunkId)
        assertEquals(1, read { database.chunks().count() })
        assertEquals(2, read { database.frames().count() })
        assertEquals(1, read { database.samples().count() })
        assertEquals(70, read { database.samples().find(ADDRESS, 7, SAMPLE_UNIX) }?.heartRateRaw)
        assertEquals(4, read { database.samples().find(ADDRESS, 7, SAMPLE_UNIX) }?.subsecondTicks)
        val kept = read { database.chunks().find(ADDRESS, token(3)) }
        assertNull(kept?.ackedAt)
        assertEquals(STORED_AT, kept?.storedUnixSeconds)
        assertNull(kept?.storedSubsecondTicks)
        assertFalse(HistoryAckGate.maySend())

        val otherToken = store.commitChunk(
            sessionId = session,
            frames = listOf(frame),
            r18Rows = listOf(row.copy(heartRateRaw = 40)),
            archived = emptyList(),
            token = token(4),
        )
        assertFalse(otherToken.duplicate)
        assertEquals(0, otherToken.framesInserted)
        assertEquals(0, otherToken.samplesInserted)
        assertEquals(2, read { database.chunks().count() })
        assertEquals(2, read { database.frames().count() })
        assertEquals(1, read { database.samples().count() })
        assertEquals(70, read { database.samples().find(ADDRESS, 7, SAMPLE_UNIX) }?.heartRateRaw)

        val laterSecond = store.commitChunk(
            sessionId = session,
            frames = emptyList(),
            r18Rows = listOf(row.copy(unixSeconds = SAMPLE_UNIX + 1, heartRateRaw = 55, heartRateBpm = 55)),
            archived = emptyList(),
            token = token(5),
        )
        assertFalse(laterSecond.duplicate)
        assertEquals(1, laterSecond.samplesInserted)
        assertEquals(2, read { database.samples().count() })
        assertEquals(70, read { database.samples().find(ADDRESS, 7, SAMPLE_UNIX) }?.heartRateRaw)
        assertEquals(55, read { database.samples().find(ADDRESS, 7, SAMPLE_UNIX + 1) }?.heartRateRaw)
    }

    @Test
    fun recordsBatteryOffTheCallerThread() {
        val store = RoomSyncStore(database, clock = clock)
        val id = store.recordBattery(
            DeviceBatteryDraft(
                deviceAddress = ADDRESS,
                unixSeconds = 90,
                subsecondTicks = 8,
                source = "command26",
                percent = 80,
                percentRaw = 80,
                millivolts = 4100,
                charging = false,
                raw = byteArrayOf(80, 0, 0, 0),
            ),
        )
        val stored = read { database.batteries().find(id) }
        assertEquals("command26", stored?.source)
        assertEquals(80L, stored?.percent)
        assertEquals(8, stored?.subsecondTicks)
        assertEquals(false, stored?.charging)
        assertEquals(1, read { database.batteries().count() })
    }

    private fun <T> read(block: () -> T): T = reads.submit(Callable(block)).get()

    private fun sample(
        recordIndex: Long,
        heartRateRaw: Int,
        heartRateBpm: Int? = heartRateRaw,
        unixSeconds: Long = SAMPLE_UNIX,
    ): R18Draft {
        return R18Draft(
            recordIndex = recordIndex,
            unixSeconds = unixSeconds,
            subsecondTicks = 4,
            heartRateBpm = heartRateBpm,
            heartRateRaw = heartRateRaw,
            rrCount = 1,
            rrTicks = intArrayOf(512),
            rrBlockRaw = byteArrayOf(0, 2),
            qualityRaw = 0x80,
            raw = byteArrayOf(18, recordIndex.toByte(), heartRateRaw.toByte()),
        )
    }

    private fun token(mark: Int): ByteArray = ByteArray(8) { mark.toByte() }

    private companion object {
        const val ADDRESS: String = "AA:BB:CC:DD:EE:FF"
        const val STARTED_AT: Long = 1_700_000_000L
        const val STORED_AT: Long = 1_700_000_010L
        const val SAMPLE_UNIX: Long = 1_700_000_100L
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeviceSchemaMigrationTest {
    @Test
    fun version1DeviceRowSurvivesSyncTables() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(NAME)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(NAME)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(DEVICE_V1)
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )
        helper.writableDatabase.execSQL(
            "INSERT INTO `device` (`address`, `helloFirmware`, `informationFirmware`, " +
                "`commandBatteryPercent`, `serviceBatteryPercent`, `updatedAtMillis`) " +
                "VALUES ('AA:BB:CC:DD:EE:FF', '50.42.1.0', NULL, 80, 81, 11)",
        )
        helper.close()

        val room = Room.databaseBuilder(context, PulseDatabase::class.java, NAME)
            .addMigrations(PulseMigrations.FROM_1_TO_2)
            .allowMainThreadQueries()
            .build()
        val stored = room.devices().latest()
        assertEquals("AA:BB:CC:DD:EE:FF", stored?.address)
        assertEquals("50.42.1.0", stored?.helloFirmware)
        assertNull(stored?.informationFirmware)
        assertEquals(80L, stored?.commandBatteryPercent)
        assertEquals(81, stored?.serviceBatteryPercent)
        assertEquals(11L, stored?.updatedAtMillis)
        assertEquals(0, room.frames().count())
        assertEquals(0, room.samples().count())
        assertEquals(0, room.chunks().count())
        room.close()
    }

    private companion object {
        const val NAME: String = "pulse-migrate.db"
        const val DEVICE_V1: String =
            "CREATE TABLE IF NOT EXISTS `device` (" +
                "`address` TEXT NOT NULL, " +
                "`helloFirmware` TEXT, " +
                "`informationFirmware` TEXT, " +
                "`commandBatteryPercent` INTEGER, " +
                "`serviceBatteryPercent` INTEGER, " +
                "`updatedAtMillis` INTEGER NOT NULL, " +
                "PRIMARY KEY(`address`))"
    }
}
