package app.pulse.sync

import app.pulse.wire.codec.ClockCommand
import app.pulse.wire.codec.PacketType

/**
 * Bounds for one history attempt. The 2-second clock window and the 60-second
 * quiet window are project rules. The record bound is the practical cap from
 * the safety rules: about 2000 history frames with no end token abort the attempt.
 */
object HistoryLimits {
    const val CLOCK_DRIFT_SECONDS: Long = 2
    const val WATCHDOG_MS: Long = 60_000
    const val MAX_RECORDS_WITHOUT_END: Int = 2_000

    /** Packet types that count as history traffic for the quiet watchdog. */
    val TRAFFIC: Set<Int> = setOf(
        PacketType.HISTORY_RECORD,
        PacketType.EVENT,
        PacketType.HISTORY_METADATA,
        PacketType.CONSOLE,
    )
}

/** How an attempt ended. [COMPLETE] is the only outcome that saw metadata complete. */
enum class SessionOutcome {
    COMPLETE,
    ABORTED,
    DISCONNECTED,
    WATCHDOG,
    BOUNDED,
}

/** Ordered trace of one attempt. Tests assert that every [Ack] follows a [Commit] of that token. */
sealed class SyncTrace {
    class Sent(val command: Int, val body: ByteArray) : SyncTrace()

    class Opened(val sessionId: Long, val range: ByteArray) : SyncTrace()

    class Commit(
        val token: ByteArray,
        val frames: Int,
        val samples: Int,
        val archived: Int,
        val duplicate: Boolean,
    ) : SyncTrace()

    class Ack(val token: ByteArray, val body: ByteArray) : SyncTrace()

    object DroppedChunk : SyncTrace()

    class Closed(val outcome: SessionOutcome) : SyncTrace()
}

/** Phone time used for drift and for timestamps this process writes. */
data class PhoneTime(
    val unixSeconds: Long,
    val ticks: Int,
)

fun interface PhoneClock {
    fun now(): PhoneTime
}

/**
 * Phone time from Unix milliseconds.
 *
 * Ticks use the set-clock rule: hundredths of a second, then
 * `floor(hundredths × 32768 / 100)`, clamped to 0..32767.
 */
fun phoneTimeFromUnixMillis(millis: Long): PhoneTime {
    val seconds = Math.floorDiv(millis, 1000L)
    val into = (millis - seconds * 1000L).toInt().coerceAtLeast(0)
    val hundredths = into / 10
    val ticks = (hundredths * 32_768) / 100
    return PhoneTime(seconds, ticks.coerceIn(0, ClockCommand.MAX_TICKS))
}

/** Bytes the engine writes, and the notifications it reads. One listener at a time is enough. */
interface HistoryLink {
    val address: String?

    /** @return false when the frame was not handed to the link. */
    fun write(frame: ByteArray): Boolean

    fun listen(
        onNotification: (source: String, bytes: ByteArray) -> Unit,
        onDown: () -> Unit,
    ): HistorySubscription
}

fun interface HistorySubscription {
    fun cancel()
}

fun interface HistoryScheduler {
    fun after(delayMs: Long, task: () -> Unit): HistorySubscription
}

/**
 * Where one history attempt is.
 *
 * [IDLE] is before Sync now. [DONE] is set when the attempt has an outcome.
 */
enum class SyncPhase {
    IDLE,
    CLOCK,
    CLOCK_SET,
    CLOCK_VERIFY,
    RANGE,
    START,
    STREAM,
    DONE,
}

/**
 * Live view of the attempt in progress.
 *
 * [chunks] counts end tokens newly stored on this attempt. A token that was
 * already stored is [chunksDuplicate], and a later copy of a token already
 * handled on this attempt is [endsRetried]. [records] counts history records
 * received, including a chunk that is later dropped. [savedSamples] and
 * [savedChunks] are the last local-storage counts. [outcome] stays null until
 * the attempt ends.
 */
data class SyncSnapshot(
    val phase: SyncPhase,
    val chunks: Int,
    val records: Int,
    val outcome: SessionOutcome?,
    val endsReceived: Int = 0,
    val chunksDuplicate: Int = 0,
    val endsRetried: Int = 0,
    val endsRejected: Int = 0,
    val ignoredCompletes: Int = 0,
    val droppedUncommitted: Boolean = false,
    val savedSamples: Int = 0,
    val savedChunks: Int = 0,
)

/**
 * Where the in-memory one-shot acknowledgement consent is.
 *
 * This is not the sync watchdog and not the production gate. [OFF] is the
 * default. [ARMED] is local consent for the next attempt. [WAITING] is that
 * consent bound to the attempt that is running. [WRITING] is the single write
 * in progress. [HANDED_OFF] means the link accepted the bytes, which is not a
 * strap result. [RADIO_REFUSED] means that write was not accepted and will not
 * be repeated. [PENDING] and [RESULT] are type-36 results. [NO_RESPONSE] means
 * the attempt ended after a handoff without a final type-36 result.
 * [REFUSED] means validation stopped the write.
 */
enum class AckPhase {
    OFF,
    ARMED,
    WAITING,
    WRITING,
    HANDED_OFF,
    RADIO_REFUSED,
    PENDING,
    RESULT,
    NO_RESPONSE,
    REFUSED,
}

/**
 * What the sync screen observes.
 *
 * [outcome] and [finishedAt] clear when a new attempt starts. A tap with no
 * strap linked leaves them in place. [chunks] counts tokens newly stored on
 * this attempt. [savedSamples] and [savedChunks] are local-storage counts.
 * [blocked] is set when Sync now is tapped with no strap linked.
 *
 * [ackPhase] is process memory. It is not restored from [PersistedChunk.ackedAt].
 * [ackResult] is a type-36 result code when one was parsed. [ackNote] is a
 * specific reason that overrides the phase sentence.
 */
data class SyncActivity(
    val phase: SyncPhase = SyncPhase.IDLE,
    val running: Boolean = false,
    val chunks: Int = 0,
    val records: Int = 0,
    val endsReceived: Int = 0,
    val chunksDuplicate: Int = 0,
    val endsRetried: Int = 0,
    val endsRejected: Int = 0,
    val ignoredCompletes: Int = 0,
    val droppedUncommitted: Boolean = false,
    val savedSamples: Int = 0,
    val savedChunks: Int = 0,
    val outcome: SessionOutcome? = null,
    val finishedAt: PhoneTime? = null,
    val blocked: String? = null,
    val ackPhase: AckPhase = AckPhase.OFF,
    val ackSamples: Int? = null,
    val ackResult: Int? = null,
    val ackNote: String? = null,
)

/**
 * One chunk this process has stored and may acknowledge once.
 *
 * [token] is the original end-token bytes. It is not rebuilt.
 */
data class DurableChunk(
    val chunkId: Long,
    val sessionId: Long,
    val token: ByteArray,
    val sampleCount: Int,
)

/** Firmware whose end-token offsets this one-shot is allowed to use. */
object OneShotAck {
    const val FIRMWARE: String = "50.43.1.0"

    fun allowsFirmware(firmware: String?): Boolean = firmware == FIRMWARE
}
