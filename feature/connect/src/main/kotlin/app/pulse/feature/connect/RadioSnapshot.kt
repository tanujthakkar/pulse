package app.pulse.feature.connect

/** What the process can see about grants and the Bluetooth radio right now. */
data class RadioSnapshot(
    val sdkInt: Int,
    val granted: Set<LinkPermission>,
    val rationale: Set<LinkPermission>,
    val bluetoothEnabled: Boolean,
)
