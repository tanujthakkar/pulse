package app.pulse.data

import app.pulse.sync.ChunkReceipt
import app.pulse.sync.HISTORY_TOKEN_BYTES
import app.pulse.sync.R18Draft
import app.pulse.sync.RawFrameDraft
import app.pulse.sync.SyncClock
import app.pulse.sync.SyncStore
import app.pulse.wire.ProtocolSpecVersion
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Room-backed [SyncStore].
 *
 * [commitChunk] runs on [executor], not on the caller thread. The device table may
 * still be read on the main thread; this store does not use that shortcut.
 * [SyncChunkRow.ackedAt] is never written here, and this class does not send
 * a history acknowledgement.
 *
 * [afterFramesInserted] runs inside the chunk transaction after frame inserts
 * and before samples. Production leaves it empty. Tests use it to fail the
 * transaction.
 */
class RoomSyncStore(
    private val database: PulseDatabase,
    private val executor: ExecutorService = newSyncExecutor(),
    private val clock: SyncClock = SyncClock { System.currentTimeMillis() / 1000L },
    private val afterFramesInserted: () -> Unit = {},
) : SyncStore {
    override fun openSession(
        deviceAddress: String,
        startedUnixSeconds: Long,
        startedSubsecondTicks: Int?,
    ): Long = onIo {
        database.sessions().insert(
            SyncSessionRow(
                deviceId = deviceAddress,
                startedUnixSeconds = startedUnixSeconds,
                startedSubsecondTicks = startedSubsecondTicks,
                endedUnixSeconds = null,
                endedSubsecondTicks = null,
            ),
        )
    }

    override fun commitChunk(
        sessionId: Long,
        frames: List<RawFrameDraft>,
        r18Rows: List<R18Draft>,
        archived: List<RawFrameDraft>,
        token: ByteArray,
    ): ChunkReceipt = onIo {
        require(token.size == HISTORY_TOKEN_BYTES) {
            "history token is $HISTORY_TOKEN_BYTES bytes"
        }
        r18Rows.forEach { draft ->
            require(draft.rrTicks.size <= 4) { "layout 18 keeps at most four R-R words" }
            require(draft.axisAccel.size == 3 && draft.axisAccelBits.size == 3) {
                "layout 18 has three acceleration axes"
            }
            require(draft.auxTempC.size == 2 && draft.auxTempRaw.size == 2) {
                "layout 18 has two auxiliary temperatures"
            }
            require(draft.statusRaw.size == 3) { "layout 18 has three status words" }
        }
        val tokenCopy = token.copyOf()
        database.runInTransaction<ChunkReceipt> {
            val session = database.sessions().find(sessionId)
                ?: error("sync session $sessionId is not stored")
            val deviceId = session.deviceId
            val existing = database.chunks().find(deviceId, tokenCopy)
            if (existing != null) {
                ChunkReceipt(
                    chunkId = existing.id,
                    sessionId = existing.sessionId,
                    framesInserted = 0,
                    samplesInserted = 0,
                    archivedInserted = 0,
                    duplicate = true,
                )
            } else {
                val chunkId = database.chunks().insert(
                    SyncChunkRow(
                        sessionId = sessionId,
                        deviceId = deviceId,
                        token = tokenCopy,
                        frameCount = frames.size,
                        sampleCount = r18Rows.size,
                        archivedCount = archived.size,
                        storedUnixSeconds = clock.unixSeconds(),
                        storedSubsecondTicks = null,
                        ackedAt = null,
                    ),
                )
                val framesInserted = insertFrames(deviceId, chunkId, frames, archived = false)
                afterFramesInserted()
                val samplesInserted = insertSamples(deviceId, chunkId, r18Rows)
                val archivedInserted = insertFrames(deviceId, chunkId, archived, archived = true)
                ChunkReceipt(
                    chunkId = chunkId,
                    sessionId = sessionId,
                    framesInserted = framesInserted,
                    samplesInserted = samplesInserted,
                    archivedInserted = archivedInserted,
                    duplicate = false,
                )
            }
        }
    }

    /**
     * Appends one battery observation on the sync executor.
     *
     * The handshake still updates the device row. This table is separate.
     */
    fun recordBattery(draft: DeviceBatteryDraft): Long = onIo {
        database.batteries().insert(
            DeviceBatteryRow(
                deviceId = draft.deviceAddress,
                unixSeconds = draft.unixSeconds,
                subsecondTicks = draft.subsecondTicks,
                source = draft.source,
                percent = draft.percent,
                percentRaw = draft.percentRaw,
                millivolts = draft.millivolts,
                charging = draft.charging,
                raw = draft.raw?.copyOf(),
            ),
        )
    }

    private fun insertFrames(
        deviceId: String,
        chunkId: Long,
        frames: List<RawFrameDraft>,
        archived: Boolean,
    ): Int {
        var inserted = 0
        for (frame in frames) {
            val payload = frame.bytes.copyOf()
            val rowId = database.frames().insert(
                RawFrameRow(
                    deviceId = deviceId,
                    chunkId = chunkId,
                    sha256 = sha256(payload),
                    packetType = frame.packetType,
                    layout = frame.layout,
                    recordIndex = frame.recordIndex,
                    unixSeconds = frame.unixSeconds,
                    subsecondTicks = frame.subsecondTicks,
                    archived = archived,
                    payload = payload,
                ),
            )
            if (rowId != IGNORED) inserted += 1
        }
        return inserted
    }

    private fun insertSamples(
        deviceId: String,
        chunkId: Long,
        rows: List<R18Draft>,
    ): Int {
        var inserted = 0
        for (draft in rows) {
            val rowId = database.samples().insert(draft.toRow(deviceId, chunkId))
            if (rowId != IGNORED) inserted += 1
        }
        return inserted
    }

    private fun <T> onIo(block: () -> T): T {
        try {
            return executor.submit(Callable(block)).get()
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("sync store interrupted", error)
        }
    }

    private companion object {
        const val IGNORED: Long = -1
    }
}

internal fun newSyncExecutor(): ExecutorService {
    return Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pulse-sync").apply { isDaemon = true }
    }
}

private fun sha256(bytes: ByteArray): ByteArray {
    return MessageDigest.getInstance("SHA-256").digest(bytes)
}

private fun finite(value: Float): Float? = if (value.isFinite()) value else null

private fun R18Draft.toRow(deviceId: String, chunkId: Long): R18SampleRow {
    return R18SampleRow(
        deviceId = deviceId,
        recordIndex = recordIndex,
        chunkId = chunkId,
        unixSeconds = unixSeconds,
        subsecondTicks = subsecondTicks,
        heartRateBpm = heartRateBpm,
        heartRateRaw = heartRateRaw,
        rrCount = rrCount,
        rrTick0 = rrTicks.getOrNull(0),
        rrTick1 = rrTicks.getOrNull(1),
        rrTick2 = rrTicks.getOrNull(2),
        rrTick3 = rrTicks.getOrNull(3),
        rrBlockRaw = rrBlockRaw.copyOf(),
        cardiacRaw = cardiacRaw,
        qualityRaw = qualityRaw,
        alternateHrRaw = alternateHeartRateRaw,
        packedWordRaw = packedWordRaw,
        dynamicAccel = dynamicAccel?.let(::finite),
        dynamicAccelBits = dynamicAccelBits,
        axisAccel0 = axisAccel.getOrNull(0)?.let(::finite),
        axisAccel1 = axisAccel.getOrNull(1)?.let(::finite),
        axisAccel2 = axisAccel.getOrNull(2)?.let(::finite),
        axisAccel0Bits = axisAccelBits.getOrElse(0) { 0 },
        axisAccel1Bits = axisAccelBits.getOrElse(1) { 0 },
        axisAccel2Bits = axisAccelBits.getOrElse(2) { 0 },
        stepCounter = stepCounter,
        cadenceRaw = cadenceRaw,
        hardwareCounter = hardwareCounter,
        activityClass = activityClass,
        activityClassRaw = activityClassRaw,
        counterOverrideRaw = counterOverrideRaw,
        auxTemp0C = auxTempC.getOrNull(0)?.let(::finite),
        auxTemp1C = auxTempC.getOrNull(1)?.let(::finite),
        auxTemp0Raw = auxTempRaw.getOrElse(0) { 0 },
        auxTemp1Raw = auxTempRaw.getOrElse(1) { 0 },
        skinTempC = skinTempC,
        skinTempRaw = skinTempRaw,
        status0Raw = statusRaw.getOrElse(0) { 0 },
        status1Raw = statusRaw.getOrElse(1) { 0 },
        status2Raw = statusRaw.getOrElse(2) { 0 },
        motionRaw = motionRaw,
        restState = restState,
        sleepAdjacentRaw = sleepAdjacentRaw,
        crc32 = crc32,
        raw = raw.copyOf(),
        decoderVersion = decoderVersion,
    )
}

object DataBuildInfo {
    const val SPEC: String = ProtocolSpecVersion.CURRENT
}
