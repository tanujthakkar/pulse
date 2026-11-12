package app.pulse.sync

import java.util.ArrayDeque

/** Why a unique history-sync request is being enqueued. */
enum class SyncChainReason {
    APP_START,
    BOOT,
    AFTER_RUN,
}

/**
 * [KEEP] leaves a pending request in place so app start and boot do not stack
 * a second chain. [REPLACE] is the follow-up after a run: one pending request,
 * whether the run completed or failed.
 */
enum class SyncChainPolicy {
    KEEP,
    REPLACE,
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
        SyncChainReason.AFTER_RUN -> SyncChainPolicy.REPLACE
    },
    requiresBatteryNotLow = true,
)

/**
 * In-memory stand-in for the unique WorkManager chain.
 *
 * [KEEP] is a no-op while a request is already pending. [REPLACE] drops that
 * pending request and keeps the new one, so a follow-up never stacks.
 */
class SyncChainLedger {
    private val pending = ArrayDeque<SyncChainRequest>()

    fun enqueue(request: SyncChainRequest) {
        when (request.policy) {
            SyncChainPolicy.KEEP -> if (pending.isEmpty()) pending.addLast(request)
            SyncChainPolicy.REPLACE -> {
                pending.clear()
                pending.addLast(request)
            }
        }
    }

    fun pending(): List<SyncChainRequest> = pending.toList()
}
