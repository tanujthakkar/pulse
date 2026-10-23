package app.pulse.data

import app.pulse.sync.SyncStore
import app.pulse.wire.ProtocolSpecVersion

/**
 * Room-backed [SyncStore] placeholder.
 *
 * The schema and the transactional commit are a later milestone.
 */
interface RoomSyncStore : SyncStore

object DataBuildInfo {
    const val SPEC: String = ProtocolSpecVersion.CURRENT
}
