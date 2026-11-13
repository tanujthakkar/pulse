package app.pulse.sync

import java.util.ArrayDeque

/** Why a unique history-sync request is being enqueued. */
enum class SyncChainReason {
    APP_START,
    BOOT,
    AFTER_RUN,
    AFTER_FINISH,
}

/**
 * [KEEP] leaves a pending request in place so app start and boot do not stack
 * a second chain. [APPEND] is the follow-up a worker adds when it returns, so
 * the running worker is not cancelled. [RESET] replaces a request that is
 * still waiting, which restarts the five-minute delay from the moment an
 * in-process sync finishes.
 */
enum class SyncChainPolicy {
    KEEP,
    APPEND,
    RESET,
}

data class SyncChainRequest(
    val name: String,
    val delayMs: Long,
    val policy: SyncChainPolicy,
    val requiresBatteryNotLow: Boolean,
)

fun syncChainRequest(reason: SyncChainReason): SyncChainRequest = SyncChainRequest(
    name = AutoSync.WORK_NAME,
    delayMs = AutoSync.INTERVAL_MS,
    policy = when (reason) {
        SyncChainReason.APP_START, SyncChainReason.BOOT -> SyncChainPolicy.KEEP
        SyncChainReason.AFTER_RUN -> SyncChainPolicy.APPEND
        SyncChainReason.AFTER_FINISH -> SyncChainPolicy.RESET
    },
    requiresBatteryNotLow = true,
)

/**
 * In-memory stand-in for the unique WorkManager chain.
 *
 * [KEEP] is a no-op while a request is already pending. [APPEND] and [RESET]
 * leave one pending request. WorkManager's APPEND_OR_REPLACE and REPLACE
 * differ while a worker is running; this ledger only tracks the pending slot.
 */
class SyncChainLedger {
    private val pending = ArrayDeque<SyncChainRequest>()

    fun enqueue(request: SyncChainRequest) {
        when (request.policy) {
            SyncChainPolicy.KEEP -> if (pending.isEmpty()) pending.addLast(request)
            SyncChainPolicy.APPEND, SyncChainPolicy.RESET -> {
                pending.clear()
                pending.addLast(request)
            }
        }
    }

    fun pending(): List<SyncChainRequest> = pending.toList()
}
