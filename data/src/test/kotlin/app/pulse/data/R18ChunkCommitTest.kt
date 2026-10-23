package app.pulse.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.pulse.sync.SyncClock
import app.pulse.sync.toR18Draft
import app.pulse.sync.toRawFrameDraft
import app.pulse.wire.codec.Crc32IsoHdlc
import app.pulse.wire.codec.DataRangeCommand
import app.pulse.wire.codec.FrameCodec
import app.pulse.wire.codec.HeaderWord
import app.pulse.wire.codec.HistoryRecord
import app.pulse.wire.codec.HistoryRecordDecoder
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A constructed layout-18 frame committed through the decoder's draft.
 *
 * Byte 9 is the layout. [FrameCodec.encode] stores its sequence argument at
 * that offset, so the call passes 18 there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class R18ChunkCommitTest {
    private lateinit var database: PulseDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PulseDatabase::class.java)
            .allowMainThreadQueries()
            .build()
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

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun decodedRowsAndAnUnknownLayoutCommitTogether() {
        val shown = HistoryRecordDecoder.decode(
            layout18(
                recordIndex = 7,
                unixSeconds = UNIX,
                heartRate = 72,
                skinRaw = 3650,
                alternateHeartRate = 111,
                sleepAdjacent = 0x5A,
                motion = 0x6B,
                activity = 2,
                rrTicks = intArrayOf(1024, 1),
            ),
        ) as HistoryRecord.Layout18
        val hidden = HistoryRecordDecoder.decode(
            layout18(
                recordIndex = 8,
                unixSeconds = UNIX,
                heartRate = 10,
                skinRaw = -5000,
                alternateHeartRate = 40,
                sleepAdjacent = 1,
                motion = 0,
                activity = 0xFF,
                rrTicks = intArrayOf(),
            ),
        ) as HistoryRecord.Layout18
        val unknownBytes = ByteArray(30) { 0xAB.toByte() }
        unknownBytes[8] = 47
        unknownBytes[9] = 20
        val unknown = HistoryRecordDecoder.decode(unknownBytes) as HistoryRecord.Unknown

        assertEquals(HistoryRecordDecoder.VERSION, shown.decoderVersion)
        assertEquals(72, shown.reading.heartRateBpm)
        assertEquals(listOf(1000, 1), shown.reading.rrMillis)
        assertNull(hidden.reading.heartRateBpm)
        assertNull(hidden.reading.skinTempC)
        assertEquals(-50f, hidden.stored.skinTempRaw.toShort().toInt() / 100f)

        val store = RoomSyncStore(database, clock = SyncClock { STORED_AT })
        val range = ByteArray(DataRangeCommand.BODY_BYTES) { 3 }
        val session = store.openSession(ADDRESS, UNIX, null, range)
        assertArrayEquals(range, database.sessions().find(session)?.rangeSnapshot)
        val receipt = store.commitChunk(
            sessionId = session,
            frames = listOf(shown.toRawFrameDraft(), hidden.toRawFrameDraft()),
            r18Rows = listOf(shown.toR18Draft(), hidden.toR18Draft()),
            archived = listOf(unknown.toRawFrameDraft()),
            token = ByteArray(8) { 4 },
        )
        assertEquals(false, receipt.duplicate)
        assertEquals(2, receipt.framesInserted)
        assertEquals(2, receipt.samplesInserted)
        assertEquals(1, receipt.archivedInserted)

        val row = database.samples().find(ADDRESS, 7, UNIX)
        assertEquals(HistoryRecordDecoder.VERSION, row?.decoderVersion)
        assertEquals(72, row?.heartRateBpm)
        assertEquals(72, row?.heartRateRaw)
        assertEquals(36.5f, row?.skinTempC)
        assertEquals(3650, row?.skinTempRaw)
        assertEquals(111, row?.alternateHrRaw)
        assertEquals(0x5A, row?.sleepAdjacentRaw)
        assertEquals(1024, row?.rrTick0)
        assertEquals(1, row?.rrTick1)
        assertNull(row?.rrTick2)
        assertEquals(2, row?.activityClass)
        assertEquals(2, row?.restState)
        assertNull(row?.subsecondTicks)
        assertEquals(124, row?.raw?.size)
        assertEquals(Crc32IsoHdlc.compute(shown.stored.raw, 8, 112), row?.crc32)

        val unavailable = database.samples().find(ADDRESS, 8, UNIX)
        assertEquals(HistoryRecordDecoder.VERSION, unavailable?.decoderVersion)
        assertNull(unavailable?.heartRateBpm)
        assertEquals(10, unavailable?.heartRateRaw)
        assertNull(unavailable?.skinTempC)
        assertEquals((-5000) and 0xFFFF, unavailable?.skinTempRaw)
        assertNull(unavailable?.activityClass)
        assertEquals(0xFF, unavailable?.activityClassRaw)

        val archived = database.frames().find(ADDRESS, sha256(unknownBytes))
        assertEquals(true, archived?.archived)
        assertEquals(20, archived?.layout)
        assertEquals(47, archived?.packetType)
        assertArrayEquals(unknownBytes, archived?.payload)
        assertNull(database.chunks().find(ADDRESS, ByteArray(8) { 4 })?.ackedAt)
        assertEquals(STORED_AT, database.chunks().find(ADDRESS, ByteArray(8) { 4 })?.storedUnixSeconds)
        assertTrue(shown.reading.heartRateBpm != shown.stored.alternateHeartRateRaw)
    }

    private fun HistoryRecord.Layout18.toRawFrameDraft() =
        app.pulse.sync.RawFrameDraft(
            bytes = stored.raw,
            packetType = 47,
            layout = 18,
            recordIndex = stored.recordIndex,
            unixSeconds = stored.unixSeconds,
        )

    private fun layout18(
        recordIndex: Long,
        unixSeconds: Long,
        heartRate: Int,
        skinRaw: Int,
        alternateHeartRate: Int,
        sleepAdjacent: Int,
        motion: Int,
        activity: Int,
        rrTicks: IntArray,
    ): ByteArray {
        val body = ByteArray(109)
        putU32(body, 0, recordIndex)
        putU32(body, 4, unixSeconds)
        body[11] = heartRate.toByte()
        body[12] = rrTicks.size.toByte()
        rrTicks.forEachIndexed { index, tick -> putU16(body, 13 + 2 * index, tick) }
        body[26] = alternateHeartRate.toByte()
        body[52] = activity.toByte()
        putU16(body, 62, skinRaw)
        body[70] = motion.toByte()
        body[71] = sleepAdjacent.toByte()
        return FrameCodec.encode(
            headerWord = HeaderWord.COMMON_REQUEST,
            packetType = 47,
            sequence = 18,
            command = 0,
            body = body,
        )
    }

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun putU32(bytes: ByteArray, offset: Int, value: Long) {
        val bits = value and 0xFFFF_FFFFL
        bytes[offset] = (bits and 0xFF).toByte()
        bytes[offset + 1] = ((bits ushr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((bits ushr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((bits ushr 24) and 0xFF).toByte()
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private companion object {
        const val ADDRESS: String = "AA:BB:CC:DD:EE:11"
        const val UNIX: Long = 1_735_689_600L
        const val STORED_AT: Long = 1_735_689_700L
    }
}
