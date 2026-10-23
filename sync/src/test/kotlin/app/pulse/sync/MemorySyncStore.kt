package app.pulse.sync

import app.pulse.wire.codec.DataRangeCommand
import java.security.MessageDigest

/** In-memory [SyncStore] with the same token, frame, and sample identity rules as Room. */
class MemorySyncStore : SyncStore {
    var failCommit: Boolean = false
    val sessions = mutableListOf<MemorySession>()
    val chunks = mutableListOf<MemoryChunk>()
    private val frameKeys = HashSet<String>()
    private val sampleKeys = HashSet<String>()
    private var nextId = 1L

    override fun openSession(
        deviceAddress: String,
        startedUnixSeconds: Long,
        startedSubsecondTicks: Int?,
        rangeSnapshot: ByteArray,
    ): Long {
        require(rangeSnapshot.size == DataRangeCommand.BODY_BYTES) {
            "data range snapshot is ${DataRangeCommand.BODY_BYTES} bytes"
        }
        val id = nextId++
        sessions += MemorySession(
            id = id,
            device = deviceAddress,
            startedUnixSeconds = startedUnixSeconds,
            startedSubsecondTicks = startedSubsecondTicks,
            rangeSnapshot = rangeSnapshot.copyOf(),
        )
        return id
    }

    override fun closeSession(
        sessionId: Long,
        endedUnixSeconds: Long,
        endedSubsecondTicks: Int?,
    ) {
        val session = sessions.first { it.id == sessionId }
        session.endedUnixSeconds = endedUnixSeconds
        session.endedSubsecondTicks = endedSubsecondTicks
    }

    override fun commitChunk(
        sessionId: Long,
        frames: List<RawFrameDraft>,
        r18Rows: List<R18Draft>,
        archived: List<RawFrameDraft>,
        token: ByteArray,
    ): ChunkReceipt {
        if (failCommit) throw IllegalStateException("store failed")
        val session = sessions.first { it.id == sessionId }
        val existing = chunks.find { it.device == session.device && it.token.contentEquals(token) }
        if (existing != null) {
            return ChunkReceipt(
                chunkId = existing.id,
                sessionId = existing.sessionId,
                framesInserted = 0,
                samplesInserted = 0,
                archivedInserted = 0,
                duplicate = true,
            )
        }
        var framesInserted = 0
        var samplesInserted = 0
        var archivedInserted = 0
        for (frame in frames) {
            if (frameKeys.add(session.device + ":" + sha(frame.bytes))) framesInserted += 1
        }
        for (row in r18Rows) {
            val key = session.device + ":" + row.recordIndex + ":" + row.unixSeconds
            if (sampleKeys.add(key)) samplesInserted += 1
        }
        for (frame in archived) {
            if (frameKeys.add(session.device + ":" + sha(frame.bytes))) archivedInserted += 1
        }
        val id = nextId++
        chunks += MemoryChunk(
            id = id,
            sessionId = sessionId,
            device = session.device,
            token = token.copyOf(),
            frames = frames.map { it.copy(bytes = it.bytes.copyOf()) },
            samples = r18Rows.toList(),
            archived = archived.map { it.copy(bytes = it.bytes.copyOf()) },
        )
        return ChunkReceipt(
            chunkId = id,
            sessionId = sessionId,
            framesInserted = framesInserted,
            samplesInserted = samplesInserted,
            archivedInserted = archivedInserted,
            duplicate = false,
        )
    }

    override fun markAcknowledged(sessionId: Long, token: ByteArray, ackedUnixSeconds: Long) {
        val session = sessions.first { it.id == sessionId }
        val chunk = chunks.find { it.device == session.device && it.token.contentEquals(token) }
        if (chunk != null) chunk.ackedAt = ackedUnixSeconds
    }

    private fun sha(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}

class MemorySession(
    val id: Long,
    val device: String,
    val startedUnixSeconds: Long,
    val startedSubsecondTicks: Int?,
    val rangeSnapshot: ByteArray,
    var endedUnixSeconds: Long? = null,
    var endedSubsecondTicks: Int? = null,
)

class MemoryChunk(
    val id: Long,
    val sessionId: Long,
    val device: String,
    val token: ByteArray,
    val frames: List<RawFrameDraft>,
    val samples: List<R18Draft>,
    val archived: List<RawFrameDraft>,
    var ackedAt: Long? = null,
)
