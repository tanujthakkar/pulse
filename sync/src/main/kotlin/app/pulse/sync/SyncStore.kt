package app.pulse.sync

/**
 * Persistence port for history sync.
 *
 * [commitChunk] stores one history chunk. An acknowledgement must not be sent
 * until that call returns. This port does not send acknowledgements.
 */
interface SyncStore {
    /**
     * Opens one sync attempt for a strap that already has a device row.
     *
     * [startedUnixSeconds] is Unix seconds UTC. [startedSubsecondTicks] is the
     * strap clock fraction in units of 1/32768 s when command 147 supplied one,
     * and null when the phone does not have that fraction.
     */
    fun openSession(
        deviceAddress: String,
        startedUnixSeconds: Long,
        startedSubsecondTicks: Int?,
    ): Long

    /**
     * Inserts frames, R18 rows, archived records, and chunk metadata for one
     * end token.
     *
     * The inserts share one transaction. A failure leaves none of that chunk's
     * rows. A token or frame or sample that is already stored is left as it
     * was.
     *
     * [token] is the original 8 bytes. It is not rebuilt from timestamps.
     */
    fun commitChunk(
        sessionId: Long,
        frames: List<RawFrameDraft>,
        r18Rows: List<R18Draft>,
        archived: List<RawFrameDraft>,
        token: ByteArray,
    ): ChunkReceipt
}

/** End-token length from spec §8. */
const val HISTORY_TOKEN_BYTES: Int = 8

/** Unix seconds for values this store writes itself, such as a chunk's stored time. */
fun interface SyncClock {
    fun unixSeconds(): Long
}

/**
 * One reassembled frame, or one archived history record, to store whole.
 *
 * The byte array is the raw companion. The other fields are optional typed
 * extracts the caller already has. This type does not decode the frame.
 */
data class RawFrameDraft(
    val bytes: ByteArray,
    val packetType: Int? = null,
    val layout: Int? = null,
    val recordIndex: Long? = null,
    val unixSeconds: Long? = null,
    val subsecondTicks: Int? = null,
)

/**
 * One layout-18 biometric row the caller has already decoded.
 *
 * Typed fields sit beside the raw companions from spec §9. Null typed fields
 * mean the value is absent or not for display. The raw fields stay filled.
 * [subsecondTicks] is the strap fraction in units of 1/32768 s when the caller
 * has one. Layout 18 itself documents Unix seconds only, so this is null unless
 * a later decoder supplies a fraction. [raw] is the record bytes.
 */
data class R18Draft(
    val recordIndex: Long,
    val unixSeconds: Long,
    val subsecondTicks: Int? = null,
    val heartRateBpm: Int? = null,
    val heartRateRaw: Int = 0,
    val rrCount: Int = 0,
    val rrTicks: IntArray = intArrayOf(),
    val rrBlockRaw: ByteArray = byteArrayOf(),
    val cardiacRaw: Int = 0,
    val qualityRaw: Int = 0,
    val alternateHeartRateRaw: Int = 0,
    val packedWordRaw: Int = 0,
    val dynamicAccel: Float? = null,
    val dynamicAccelBits: Int = 0,
    val axisAccel: FloatArray = floatArrayOf(Float.NaN, Float.NaN, Float.NaN),
    val axisAccelBits: IntArray = intArrayOf(0, 0, 0),
    val stepCounter: Int = 0,
    val cadenceRaw: Int = 0,
    val hardwareCounter: Int = 0,
    val activityClass: Int? = null,
    val activityClassRaw: Int = 0,
    val counterOverrideRaw: Int = 0,
    val auxTempC: FloatArray = floatArrayOf(Float.NaN, Float.NaN),
    val auxTempRaw: IntArray = intArrayOf(0, 0),
    val skinTempC: Float? = null,
    val skinTempRaw: Int = 0,
    val statusRaw: IntArray = intArrayOf(0, 0, 0),
    val motionRaw: Int = 0,
    val restState: Int? = null,
    val sleepAdjacentRaw: Int = 0,
    val crc32: Long = 0,
    val raw: ByteArray = byteArrayOf(),
)

/** Result of [SyncStore.commitChunk]. [duplicate] means the token was already stored. */
data class ChunkReceipt(
    val chunkId: Long,
    val sessionId: Long,
    val framesInserted: Int,
    val samplesInserted: Int,
    val archivedInserted: Int,
    val duplicate: Boolean,
)
