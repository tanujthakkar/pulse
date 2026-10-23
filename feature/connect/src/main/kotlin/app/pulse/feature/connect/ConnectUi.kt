package app.pulse.feature.connect

/** What Connect is allowed to show. Only [Ready] is the pairing screen. */
sealed interface ConnectUi {
    data object Checking : ConnectUi

    /** Fresh install: the system dialog has not been answered yet. */
    data class NeedsGrant(val permissions: List<LinkPermission>) : ConnectUi

    /**
     * At least one required permission was denied.
     *
     * [canAskAgain] is true when the platform will still show its dialog.
     * The settings deep link is always available from this screen.
     */
    data class Rationale(
        val missing: List<LinkPermission>,
        val canAskAgain: Boolean,
    ) : ConnectUi

    data object BluetoothOff : ConnectUi

    data object Ready : ConnectUi
}
