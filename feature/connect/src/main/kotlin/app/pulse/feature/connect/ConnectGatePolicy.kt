package app.pulse.feature.connect

/**
 * Decides whether Connect may proceed.
 *
 * Missing grants win over the radio: the enable prompt is only shown once
 * every required permission is granted. A permission the user has already
 * been asked about, or that the platform will explain, is a denial.
 */
object ConnectGatePolicy {
    fun evaluate(snapshot: RadioSnapshot, prompted: Set<LinkPermission>): ConnectUi {
        val missing = LinkPermission.requiredFor(snapshot.sdkInt)
            .filter { it !in snapshot.granted }
        if (missing.isNotEmpty()) {
            val denied = missing.any { it in snapshot.rationale || it in prompted }
            return if (denied) {
                ConnectUi.Rationale(
                    missing = missing,
                    canAskAgain = missing.any { it in snapshot.rationale },
                )
            } else {
                ConnectUi.NeedsGrant(missing)
            }
        }
        if (!snapshot.bluetoothEnabled) return ConnectUi.BluetoothOff
        return ConnectUi.Ready
    }
}
