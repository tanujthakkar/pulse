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
    val ackSent: Int = 0,
    val ackResult1: Int = 0,
    val ackResult0: Int = 0,
    val ackPending: Int = 0,
    val ackUnanswered: Int = 0,
    val ackRadioRefused: Int = 0,
    val ackSkipped: Int = 0,
    val ackEligibleRecords: Int = 0,
    val lastAckResult: Int? = null,
    val lastAckKind: AckKind = AckKind.NONE,
    val ackNote: String? = null,
)

/**
 * The latest acknowledgement event on an attempt.
 *
 * [HANDED_OFF] means the link accepted the bytes. That is not a strap result.
 * [RESULT] is a final type-36 code. [NO_RESPONSE] means the attempt ended
 * with no final code. [SKIPPED] means validation or the switch sent nothing.
 */
enum class AckKind {
    NONE,
    HANDED_OFF,
    RADIO_REFUSED,
    PENDING,
    RESULT,
    NO_RESPONSE,
    SKIPPED,
}

/**
 * Hello firmware this build will acknowledge.
 *
 * 50.43.1.0 accepted a command 23 sent after the chunk was stored, returned
 * result 1, and the following sync no longer offered that chunk. Other
 * firmware versions are saved and not acknowledged.
 */
object HistoryAckFirmware {
    const val ALLOWED: String = "50.43.1.0"

    fun allows(firmware: String?): Boolean = firmware == ALLOWED
}

/**
 * The user kill switch for history acknowledgement.
 *
 * The default is on. Turning it off stops new command 23 writes and does not
 * erase chunks already stored.
 */
interface HistoryAckEnabled {
    fun enabled(): Boolean

    fun setEnabled(enabled: Boolean)
}

/**
 * Sentences the Sync screen and the debug export share.
 *
 * GATT status 0 is named as a radio result. It is never described as a strap
 * result.
 */
fun historyAckReport(activity: SyncActivity): String {
    if (!activity.historyAckEnabled && activity.ackSent == 0 && activity.ackNote == null) {
        return "History acknowledgement is off. Sync saves chunks and does not send command 23."
    }
    val parts = mutableListOf(
        "Strap result 1: ${activity.ackResult1} chunks. " +
            "Layout-18 rows the strap may reclaim: ${activity.ackEligibleRecords}.",
    )
    if (activity.ackResult0 > 0) {
        parts += "Strap result 0: ${activity.ackResult0}. The boundary was not updated. Not retried this sync."
    }
    if (activity.ackPending > 0) {
        parts += "Pending: ${activity.ackPending}. Pending is not success."
    }
    if (activity.ackUnanswered > 0) {
        parts += "No strap result: ${activity.ackUnanswered}. That is not success and is not retried this sync."
    }
    if (activity.ackRadioRefused > 0) {
        parts += "The radio refused ${activity.ackRadioRefused}. Not retried this sync."
    }
    if (activity.ackSkipped > 0) {
        parts += "Not sent: ${activity.ackSkipped}."
    }
    if (activity.lastAckKind == AckKind.HANDED_OFF) {
        parts += "The last acknowledgement was passed to the radio. " +
            "GATT status 0 and a successful write are not a strap result."
    }
    if (activity.lastAckKind == AckKind.RESULT && activity.lastAckResult == 1) {
        parts += "The last strap result was 1. That can let the strap reclaim that chunk. " +
            "It does not prove the reclaim finished."
    }
    activity.ackNote?.let { parts += it }
    parts += "GATT status 0 is a radio result. It is not a strap result."
    return parts.joinToString(separator = " ")
}

/** In-memory switch for tests and for a host that has not been given storage. */
class MemoryHistoryAck(private var on: Boolean = true) : HistoryAckEnabled {
    override fun enabled(): Boolean = on

    override fun setEnabled(enabled: Boolean) {
        on = enabled
    }
}

/**
 * What the sync screen observes.
 *
 * [outcome] and [finishedAt] clear when a new attempt starts. A tap with no
 * strap linked leaves them in place. [chunks] counts tokens newly stored on
 * this attempt. [savedSamples] and [savedChunks] are local-storage counts.
 * [blocked] is set when Sync now is tapped with no strap linked.
 *
 * Acknowledgement counters are for this attempt. [ackResult1] counts strap
 * result 1 only. [ackEligibleRecords] is the layout-18 rows in those chunks.
 * A stored acknowledgement time is not a consent flag and is not read to
 * decide whether to send.
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
    val historyAckEnabled: Boolean = true,
    val ackSent: Int = 0,
    val ackResult1: Int = 0,
    val ackResult0: Int = 0,
    val ackPending: Int = 0,
    val ackUnanswered: Int = 0,
    val ackRadioRefused: Int = 0,
    val ackSkipped: Int = 0,
    val ackEligibleRecords: Int = 0,
    val lastAckResult: Int? = null,
    val lastAckKind: AckKind = AckKind.NONE,
    val ackNote: String? = null,
)
