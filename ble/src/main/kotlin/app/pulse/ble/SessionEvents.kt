package app.pulse.ble

import java.util.UUID

enum class SessionPhase {
    IDLE,
    OPENING,
    READY,
    CLOSING,
    CLOSED,
}

enum class CloseReason {
    REQUESTED,
    LINK_LOST,
    FAILED,
    TIMED_OUT,
}

/**
 * Callbacks from [GattSession]. Invoked on the session queue, after the state
 * they describe has been published. Do not block waiting for another session
 * call; [GattSession.write] and [GattSession.disconnect] from these methods
 * are queued behind the current step.
 */
interface SessionEvents {
    fun onReady(mtu: Int) {}

    fun onNotification(characteristic: UUID, value: ByteArray) {}

    fun onWriteFinished(characteristic: UUID, status: Int) {}

    fun onReadFinished(characteristic: UUID, status: Int, value: ByteArray) {}

    fun onClosed(reason: CloseReason) {}
}
