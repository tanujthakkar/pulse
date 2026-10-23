package app.pulse.link

/**
 * When [StrapLinkService] should stay in the foreground.
 *
 * The service is promoted as soon as the link leaves idle, while the activity
 * is still in front. Android 12 and later will not allow startForegroundService
 * once the user has left.
 * It stays up through reconnect scans and stops on a user disconnect or any
 * other clean teardown ([LinkPhase.IDLE], [LinkPhase.PAIRING_HELP],
 * [LinkPhase.FAILED]).
 */
internal object LinkForegroundPolicy {
    fun shouldRun(phase: LinkPhase): Boolean = when (phase) {
        LinkPhase.SCANNING,
        LinkPhase.BONDING,
        LinkPhase.CONNECTING,
        LinkPhase.LINKED,
        -> true
        LinkPhase.IDLE,
        LinkPhase.PAIRING_HELP,
        LinkPhase.FAILED,
        -> false
    }
}

/**
 * One line for the ongoing strap notification.
 *
 * The product name is Pulse. Battery, when the handshake has one, is appended
 * for a connected strap and while history is syncing.
 */
internal fun linkStatusLine(snapshot: LinkSnapshot, syncing: Boolean): String {
    val base = if (syncing) {
        "Syncing strap history"
    } else {
        when (snapshot.phase) {
            LinkPhase.SCANNING -> "Looking for a strap"
            LinkPhase.BONDING -> "Pairing with the strap"
            LinkPhase.CONNECTING -> "Connecting to the strap"
            LinkPhase.LINKED -> if (snapshot.hello == HelloProgress.FAILED) {
                "Strap connected · setup incomplete"
            } else {
                "Strap connected"
            }
            LinkPhase.IDLE,
            LinkPhase.PAIRING_HELP,
            LinkPhase.FAILED,
            -> "Pulse"
        }
    }
    val battery = snapshot.serviceBatteryPercent?.toLong() ?: snapshot.commandBatteryPercent
    val showBattery = battery != null && (syncing || snapshot.phase == LinkPhase.LINKED)
    return if (showBattery) "$base · $battery% battery" else base
}
