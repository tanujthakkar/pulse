package app.pulse.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import app.pulse.sync.R18Draft
import app.pulse.wire.codec.DataRangeCommand
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
class EdgeImporterTest {
    private lateinit var database: PulseDatabase
    private lateinit var file: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PulseDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        file = File(context.cacheDir, "edge-fixture.db")
    }

    @After
    fun tearDown() {
        database.close()
        file.delete()
    }

    @Test
    fun importPreservesRowsAndASecondRunDoesNotDuplicate() {
        EdgeFixture.write(file)
        val before = file.readBytes()
        val importer = EdgeImporter(database, clock = { 50L }, newId = { "batch-1" })
        val opened = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        val writeError = assertThrows(SQLiteException::class.java) {
            opened.execSQL("CREATE TABLE should_fail(id INTEGER)")
        }
        opened.close()
        assertTrue(writeError.message.orEmpty().isNotEmpty())

        val preview = importer.preview(file)
        assertEquals(58, preview.userVersion)
        assertEquals(edgeFileSha256(file), preview.sha256)
        assertEquals(3L, preview.categories.named(EdgeCategory.ONE_HZ).rows + preview.categories.named(EdgeCategory.SAMPLES_ONLY).rows)
        assertEquals(2L, preview.categories.named(EdgeCategory.ONE_HZ).rows)
        assertEquals(1L, preview.categories.named(EdgeCategory.SAMPLES_ONLY).rows)
        assertEquals(2L, preview.categories.named(EdgeCategory.RR).rows)
        assertEquals(2L, preview.categories.named(EdgeCategory.WORKOUTS).rows)
        assertEquals(2L, preview.categories.named(EdgeCategory.POINTS).rows)
        assertEquals(1L, preview.categories.named(EdgeCategory.SPLITS).rows)
        assertEquals(1L, preview.categories.named(EdgeCategory.SETS).rows)
        assertEquals(2L, preview.categories.named(EdgeCategory.SLEEP).rows)
        assertEquals(1L, preview.categories.named(EdgeCategory.SCORES).rows)
        assertEquals(1L, preview.categories.named(EdgeCategory.ARCHIVES).rows)
        assertTrue(preview.categories.named(EdgeCategory.ARCHIVES).detail.orEmpty().contains("not decoded"))
        assertTrue(preview.categories.named(EdgeCategory.ARCHIVES).detail.orEmpty().contains("100"))
        assertEquals(EdgeFixture.T_SEC * 1000 - 86_400_000, preview.rangeStartEpochMs)
        assertEquals(0, database.edgeImport().oneHzCount())

        val first = importer.commit(file)
        assertArrayEquals(before, file.readBytes())
        assertEquals("batch-1", first.batchId)
        assertEquals(0, first.archivesDecoded)
        assertEquals(1, first.archivesStored)
        assertEquals(100L, first.archivesDeclaredOneHz)
        assertEquals(40L, first.archivesDeclaredRr)
        assertEquals(3, first.categories.named(EdgeCategory.ONE_HZ).inserted)
        assertEquals(0, first.categories.named(EdgeCategory.ONE_HZ).alreadyPresent)
        assertEquals(2, first.categories.named(EdgeCategory.RR).inserted)
        assertEquals(EDGE_IMPORT_SOURCE, database.edgeImport().batch("batch-1")?.source)
        assertEquals(58, database.edgeImport().batch("batch-1")?.userVersion)

        val one = database.edgeImport().oneHz(EdgeFixture.DEVICE, EdgeFixture.T_MS)
        assertEquals(61L, one?.hr)
        assertEquals(0.1, one?.ax)
        assertEquals(0.2, one?.ay)
        assertEquals(0.3, one?.az)
        assertEquals(11L, one?.spo2RedRaw)
        assertEquals(12L, one?.spo2IrRaw)
        assertEquals(13L, one?.skinTempRaw)
        assertEquals(100L, one?.stepCount)
        assertEquals(80L, one?.stepCadence)
        assertEquals(2L, one?.activityClass)
        assertEquals(33.5, one?.skinTempC)
        assertEquals(1L, one?.onWrist)
        assertEquals(1L, one?.hrValid)
        assertEquals(60L, one?.hrAlt)
        assertEquals(14L, one?.ambientRaw)
        assertEquals("gen5", one?.deviceFamily)
        assertEquals("history", one?.edgeSource)
        assertEquals(33.1, one?.tempCh2C)
        assertEquals(32.9, one?.tempCh3C)
        assertEquals(0.01, one?.signalQualityLogvar)
        assertEquals(0.05, one?.dynAccelG)
        assertEquals(0L, one?.tsSubsec)
        assertEquals(0L, one?.bandSleepState)
        assertEquals(EDGE_RECORD_ONE_HZ, one?.recordKind)
        assertEquals(EDGE_IMPORT_SOURCE, one?.source)
        assertEquals("batch-1", one?.importBatchId)

        val sampleOnly = database.edgeImport().oneHz(EdgeFixture.DEVICE, EdgeFixture.T_MS + 2000)
        assertEquals(63L, sampleOnly?.hr)
        assertEquals(EDGE_RECORD_SAMPLES, sampleOnly?.recordKind)
        assertNull(sampleOnly?.ax)
        assertEquals(EdgeFixture.T_SEC + 2, sampleOnly?.recTs)
        assertEquals(61L, database.edgeImport().oneHz(EdgeFixture.DEVICE, EdgeFixture.T_MS)?.hr)

        val rr = database.edgeImport().rr(EdgeFixture.DEVICE, EdgeFixture.T_MS, 0)
        assertEquals(800L, rr?.rrMs)
        assertEquals(EdgeFixture.T_MS + 10, rr?.beatTsMs)
        assertEquals("history", rr?.edgeSource)
        assertEquals(EDGE_IMPORT_SOURCE, rr?.source)

        val run = database.edgeImport().workout("run-1")
        assertEquals("running", run?.type)
        assertEquals("manual", run?.edgeSource)
        assertEquals(EDGE_IMPORT_SOURCE, run?.source)
        assertEquals(200.5, run?.calories)
        assertEquals(5.5, run?.strain)
        assertEquals(160L, run?.maxHr)
        assertEquals(EdgeFixture.ZONE_JSON, run?.zoneMinJson)
        assertEquals(1000L, run?.steps)
        assertEquals(170L, run?.cadenceSpm)
        assertEquals(20.5, run?.hrrBpm)
        assertEquals(0L, run?.isPrivate)
        assertEquals(EdgeFixture.TRACE_JSON, run?.traceJson)
        assertEquals(600L, run?.traceSamples)
        assertEquals(7.5, run?.rpe)
        assertEquals(140L, run?.avgHr)
        assertEquals(45.5, run?.vo2maxEstimate)
        assertEquals(0L, run?.endTsFabricated)
        assertEquals("weight_training", database.edgeImport().workout("str-1")?.type)

        val point = database.edgeImport().point("run-1", 0)
        assertEquals(40.0, point?.lat)
        assertEquals(-70.0, point?.lng)
        assertEquals(10.0, point?.alt)
        assertEquals(5.0, point?.accuracy)
        assertEquals(3.0, point?.speed)
        assertEquals(EDGE_IMPORT_SOURCE, point?.source)

        val split = database.edgeImport().split("run-1", 1)
        assertEquals(1000.5, split?.meters)
        assertEquals(300L, split?.durationSec)
        assertEquals(150.0, split?.avgHr)
        assertEquals(4.0, split?.netElevM)

        val set = database.edgeImport().set("str-1", 0)
        assertEquals("squat", set?.exerciseKey)
        assertEquals(1L, set?.setIndex)
        assertEquals(5L, set?.reps)
        assertEquals(40.0, set?.loadKg)
        assertEquals(8L, set?.rpe)
        assertEquals(60L, set?.restSec)
        assertEquals("easy", set?.note)
        assertEquals(EDGE_IMPORT_SOURCE, set?.source)

        val sleep = database.edgeImport().sleep(EdgeFixture.DAY, 3)
        assertEquals(EdgeFixture.SLEEP_JSON, sleep?.payloadJson)
        assertEquals(1_762_900_000L, sleep?.onsetTs)
        assertEquals(1_762_930_000L, sleep?.offsetTs)
        val plain = database.edgeImport().sleep(EdgeFixture.OTHER_DAY, 1)
        assertEquals("not-json", plain?.payloadJson)
        assertNull(plain?.onsetTs)
        assertNull(plain?.offsetTs)

        val score = database.edgeImport().score(EdgeFixture.DAY, 3)
        assertEquals(EdgeFixture.SCORE_JSON, score?.payloadJson)
        assertEquals("{}", score?.windowJson)
        assertEquals(48.5, score?.rhr)
        assertEquals(41.25, score?.rmssd)
        assertNull(score?.readiness)
        assertEquals(1L, score?.finalized)
        assertEquals(EDGE_IMPORT_SOURCE, score?.source)
        assertEquals(41.25, database.edgeImport().metric(EdgeFixture.DAY, "hrv")?.value)
        assertEquals("night", database.edgeImport().method(EdgeFixture.DAY, "hrv")?.metricClass)
        assertEquals("rmssd", database.edgeImport().method(EdgeFixture.DAY, "hrv")?.method)
        assertEquals("decoded", database.edgeImport().version(EdgeFixture.DAY)?.edgeSource)
        assertEquals(EdgeFixture.WAKE_JSON, database.edgeImport().wake(EdgeFixture.DAY, 3)?.payloadJson)

        val archive = database.edgeImport().archive(EdgeFixture.DEVICE, 20747)
        assertArrayEquals(EdgeFixture.ARCHIVE_BLOB, archive?.blob)
        assertEquals(0L, archive?.decoded)
        assertEquals(5000L, archive?.rawBytes)
        assertEquals(100L, archive?.oneHzRows)
        assertEquals(40L, archive?.rrRows)
        assertEquals(1L, archive?.codec)
        assertEquals(-240L, archive?.tzOffsetMin)
        assertEquals(EDGE_IMPORT_SOURCE, archive?.source)
        assertEquals(0, database.edgeImport().oneHzWithOtherSource(EDGE_IMPORT_SOURCE))
        assertEquals(3, database.edgeImport().oneHzCount())

        val changed = File(file.parentFile, "edge-fixture-changed.db")
        EdgeFixture.write(changed, firstHr = 70)
        val second = EdgeImporter(database, clock = { 60L }, newId = { "batch-2" }).commit(changed)
        assertEquals(0, second.categories.named(EdgeCategory.ONE_HZ).inserted)
        assertEquals(3, second.categories.named(EdgeCategory.ONE_HZ).alreadyPresent)
        assertEquals(61L, database.edgeImport().oneHz(EdgeFixture.DEVICE, EdgeFixture.T_MS)?.hr)
        assertEquals(3, database.edgeImport().oneHzCount())
        assertEquals(2, database.edgeImport().batchCount())
        assertEquals("batch-1", database.edgeImport().oneHz(EdgeFixture.DEVICE, EdgeFixture.T_MS)?.importBatchId)
        changed.delete()
        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun failureRollsBackAndLeavesTheEarlierImport() {
        EdgeFixture.write(file)
        EdgeImporter(database, newId = { "kept" }).commit(file)
        val extra = File(file.parentFile, "edge-fixture-extra.db")
        EdgeFixture.write(extra, extraOneHz = true)
        val failing = EdgeImporter(database, afterCategory = { step ->
            if (step == EdgeImportStep.ONE_HZ) error("injected")
        })
        val error = assertThrows(IllegalStateException::class.java) { failing.commit(extra) }
        assertEquals("injected", error.message)
        assertEquals(3, database.edgeImport().oneHzCount())
        assertNull(database.edgeImport().oneHz(EdgeFixture.DEVICE, EdgeFixture.T_MS + 3000))
        assertEquals(1, database.edgeImport().batchCount())
        assertEquals("kept", database.edgeImport().batch("kept")?.id)
        assertEquals(2, database.edgeImport().rrCount())
        extra.delete()
    }

    @Test
    fun strapSampleWinsOnOverlapAndStillWinsIfItArrivesLater() {
        database.devices().upsert(
            DeviceRow(
                address = ADDRESS,
                helloFirmware = "50.42.1.0",
                informationFirmware = null,
                commandBatteryPercent = null,
                serviceBatteryPercent = null,
                updatedAtMillis = 1,
            ),
        )
        val store = RoomSyncStore(database)
        val session = store.openSession(ADDRESS, 1L, null, ByteArray(DataRangeCommand.BODY_BYTES))
        store.commitChunk(
            sessionId = session,
            frames = emptyList(),
            r18Rows = listOf(strap(EdgeFixture.T_SEC, 155)),
            archived = emptyList(),
            token = byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0),
        )
        EdgeFixture.write(file)
        val result = EdgeImporter(database, newId = { "edge" }).commit(file)
        assertEquals(1, result.categories.named(EdgeCategory.ONE_HZ).skippedStrapOverlap)
        assertEquals(2, result.categories.named(EdgeCategory.ONE_HZ).inserted)
        assertEquals(1, result.categories.named(EdgeCategory.RR).skippedStrapOverlap)
        assertNull(database.edgeImport().oneHz(EdgeFixture.DEVICE, EdgeFixture.T_MS))
        assertEquals(62L, database.edgeImport().oneHz(EdgeFixture.DEVICE, EdgeFixture.T_MS + 1000)?.hr)
        assertNull(database.edgeImport().rr(EdgeFixture.DEVICE, EdgeFixture.T_MS, 0))
        assertEquals(810L, database.edgeImport().rr(EdgeFixture.DEVICE, EdgeFixture.T_MS + 1000, 0)?.rrMs)
        assertEquals(155, database.samples().find(ADDRESS, EdgeFixture.T_SEC, EdgeFixture.T_SEC)?.heartRateRaw)
        val atStrap = database.heartRateAt(EdgeFixture.T_SEC)
        assertEquals(STRAP_SAMPLE_SOURCE, atStrap?.source)
        assertEquals(155L, atStrap?.bpm)
        val atEdge = database.heartRateAt(EdgeFixture.T_SEC + 1)
        assertEquals(EDGE_IMPORT_SOURCE, atEdge?.source)
        assertEquals(62L, atEdge?.bpm)

        store.commitChunk(
            sessionId = session,
            frames = emptyList(),
            r18Rows = listOf(strap(EdgeFixture.T_SEC + 1, 140)),
            archived = emptyList(),
            token = byteArrayOf(2, 0, 0, 0, 0, 0, 0, 0),
        )
        val later = database.heartRateAt(EdgeFixture.T_SEC + 1)
        assertEquals(STRAP_SAMPLE_SOURCE, later?.source)
        assertEquals(140L, later?.bpm)
        assertEquals(62L, database.edgeImport().oneHz(EdgeFixture.DEVICE, EdgeFixture.T_MS + 1000)?.hr)
        assertEquals(155, database.samples().find(ADDRESS, EdgeFixture.T_SEC, EdgeFixture.T_SEC)?.heartRateRaw)
    }

    @Test
    fun wrongUserVersionAndAMissingTableWriteNothing() {
        EdgeFixture.write(file, userVersion = 57)
        val importer = EdgeImporter(database)
        val version = assertThrows(EdgeBackupException::class.java) { importer.preview(file) }
        assertTrue(version.message.orEmpty().contains("user_version is 57"))
        assertThrows(EdgeBackupException::class.java) { importer.commit(file) }
        assertEquals(0, database.edgeImport().batchCount())
        assertEquals(0, database.edgeImport().oneHzCount())

        EdgeFixture.write(file, omitTable = "decoded_onehz")
        val missing = assertThrows(EdgeBackupException::class.java) { importer.commit(file) }
        assertTrue(missing.message.orEmpty().contains("decoded_onehz"))
        assertEquals(0, database.edgeImport().batchCount())
    }

    @Test
    fun version4RowsSurviveTheImportTables() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(MIGRATED)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(MIGRATED)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(4) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            db.execSQL(DEVICE_V1)
                            PulseMigrations.FROM_1_TO_2.migrate(db)
                            PulseMigrations.FROM_2_TO_3.migrate(db)
                            PulseMigrations.FROM_3_TO_4.migrate(db)
                        }

                        override fun onUpgrade(
                            db: androidx.sqlite.db.SupportSQLiteDatabase,
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
                "VALUES ('$ADDRESS', '50.42.1.0', NULL, 80, 81, 11)",
        )
        helper.writableDatabase.execSQL(
            "INSERT INTO `sync_session` (`device_id`, `started_unix_seconds`, " +
                "`started_subsecond_ticks`, `ended_unix_seconds`, `ended_subsecond_ticks`, " +
                "`range_snapshot`) VALUES ('$ADDRESS', 1700000000, 32, NULL, NULL, X'0102')",
        )
        helper.writableDatabase.execSQL(
            "INSERT INTO `r18_sample` (`device_id`, `record_index`, `chunk_id`, `unix_s`, " +
                "`heart_rate_raw`, `rr_count`, `rr_block_raw`, `cardiac_raw`, `quality_raw`, " +
                "`alternate_hr_raw`, `packed_word_raw`, `dynamic_accel_bits`, " +
                "`axis_accel_0_bits`, `axis_accel_1_bits`, `axis_accel_2_bits`, `step_counter`, " +
                "`cadence_raw`, `hardware_counter`, `activity_class_raw`, `counter_override_raw`, " +
                "`aux_temp_0_raw`, `aux_temp_1_raw`, `skin_temp_raw`, `status_0_raw`, " +
                "`status_1_raw`, `status_2_raw`, `motion_raw`, `sleep_adjacent_raw`, `crc32`, `raw`, " +
                "`decoder_version`) VALUES ('$ADDRESS', 3, 1, 100, 60, 0, X'00', 0, 0, 9, 0, 0, 0, 0, 0, " +
                "0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, X'12', 7)",
        )
        helper.close()

        val room = Room.databaseBuilder(context, PulseDatabase::class.java, MIGRATED)
            .addMigrations(PulseMigrations.FROM_4_TO_5)
            .allowMainThreadQueries()
            .build()
        assertEquals("50.42.1.0", room.devices().latest()?.helloFirmware)
        assertEquals(80L, room.devices().latest()?.commandBatteryPercent)
        val session = room.sessions().find(1)
        assertEquals(1_700_000_000L, session?.startedUnixSeconds)
        assertEquals(32, session?.startedSubsecondTicks)
        assertArrayEquals(byteArrayOf(1, 2), session?.rangeSnapshot)
        val sample = room.samples().find(ADDRESS, 3, 100)
        assertEquals(60, sample?.heartRateRaw)
        assertEquals(9, sample?.alternateHrRaw)
        assertEquals(7, sample?.decoderVersion)
        assertEquals(0, room.edgeImport().oneHzCount())

        val backup = File(context.cacheDir, "edge-migrated.db")
        EdgeFixture.write(backup)
        val imported = EdgeImporter(room, newId = { "after-migrate" }).commit(backup)
        assertEquals(3, imported.categories.named(EdgeCategory.ONE_HZ).inserted)
        assertEquals(60, room.samples().find(ADDRESS, 3, 100)?.heartRateRaw)
        assertEquals(7, room.samples().find(ADDRESS, 3, 100)?.decoderVersion)
        assertArrayEquals(byteArrayOf(1, 2), room.sessions().find(1)?.rangeSnapshot)
        assertEquals("50.42.1.0", room.devices().latest()?.helloFirmware)
        assertEquals(EDGE_IMPORT_SOURCE, room.edgeImport().oneHz(EdgeFixture.DEVICE, EdgeFixture.T_MS)?.source)
        room.close()
        backup.delete()
        context.deleteDatabase(MIGRATED)
    }

    @Test
    fun sleepBoundsReadNumbersAndLeaveOtherPayloadsAlone() {
        assertEquals(10L to 20L, edgeSleepBounds("""{"onset_ts":10,"offset_ts":20}"""))
        assertEquals(10L to 20L, edgeSleepBounds("""{"sleep_json":{"onset":10,"offset":20}}"""))
        assertEquals(3L to null, edgeSleepBounds("""{"onset_ts":"no","onset":3}"""))
        assertEquals(null to null, edgeSleepBounds("not-json"))
        assertEquals(null to null, edgeSleepBounds("{}"))
    }

    private fun strap(unixSeconds: Long, bpm: Int): R18Draft = R18Draft(
        recordIndex = unixSeconds,
        unixSeconds = unixSeconds,
        heartRateBpm = bpm,
        heartRateRaw = bpm,
        raw = byteArrayOf(bpm.toByte()),
    )

    private fun List<EdgeCategoryPreview>.named(name: String): EdgeCategoryPreview =
        first { it.name == name }

    private fun List<EdgeCategoryResult>.named(name: String): EdgeCategoryResult =
        first { it.name == name }

    private companion object {
        const val ADDRESS: String = "AA:BB:CC:DD:EE:FF"
        const val MIGRATED: String = "pulse-edge-migrate.db"
        const val DEVICE_V1: String =
            "CREATE TABLE IF NOT EXISTS `device` (`address` TEXT NOT NULL, `helloFirmware` TEXT, " +
                "`informationFirmware` TEXT, `commandBatteryPercent` INTEGER, " +
                "`serviceBatteryPercent` INTEGER, `updatedAtMillis` INTEGER NOT NULL, " +
                "PRIMARY KEY(`address`))"
    }
}
