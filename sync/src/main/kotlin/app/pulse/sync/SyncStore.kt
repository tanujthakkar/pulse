package app.pulse.sync

/**
 * Persistence port for history sync.
 *
 * The transactional store is a later milestone. An acknowledgement must not be
 * sent until an implementation of this port has committed the chunk.
 */
interface SyncStore
