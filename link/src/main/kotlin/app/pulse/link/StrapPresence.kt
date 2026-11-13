package app.pulse.link

import app.pulse.sync.SyncActivity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn

/**
 * What the home strap icon draws.
 *
 * [charging] is true only after event 7 while the link is up. A battery
 * percent by itself does not turn it on. [chunks] is how many history chunks
 * this attempt has received. A chunk that was already stored still counts, so
 * a resync shows the number while frames arrive. There is no total on the
 * strap, so it is a count, not a fraction.
 */
data class StrapPresence(
    val connected: Boolean = false,
    val charging: Boolean = false,
    val batteryPercent: Int? = null,
    val syncing: Boolean = false,
    val chunks: Int = 0,
)

fun strapPresence(
    link: LinkSnapshot,
    running: Boolean,
    chunks: Int,
    charging: Boolean?,
): StrapPresence {
    val connected = link.phase == LinkPhase.LINKED
    val percent = link.serviceBatteryPercent ?: link.commandBatteryPercent?.toInt()
    return StrapPresence(
        connected = connected,
        charging = connected && charging == true,
        batteryPercent = percent,
        syncing = running,
        chunks = chunks,
    )
}

/**
 * Home reads this flow. The combine runs on [Dispatchers.Default], off the
 * main thread. The inputs are in-memory: link phase, the sync attempt, and
 * the parsed charging flag.
 */
@Singleton
class StrapPresenceSource @Inject constructor(
    link: StrapLink,
    sync: HistorySyncHost,
    charge: ChargeSignals,
) {
    val presence: Flow<StrapPresence> = combine(
        link.states,
        sync.activity,
        charge.charging,
    ) { snapshot, activity, charging ->
        presenceOf(snapshot, activity, charging)
    }.flowOn(Dispatchers.Default)

    private fun presenceOf(
        snapshot: LinkSnapshot,
        activity: SyncActivity,
        charging: Boolean?,
    ): StrapPresence = strapPresence(snapshot, activity.running, syncingChunkCount(activity), charging)
}

/**
 * Chunks to draw while a sync is running.
 *
 * [SyncActivity.chunks] counts tokens newly stored. A resync of history that
 * is already on disk leaves that at zero while [SyncActivity.endsReceived]
 * and the record count climb. The icon uses the larger of the two.
 */
fun syncingChunkCount(activity: SyncActivity): Int = maxOf(activity.endsReceived, activity.chunks)
