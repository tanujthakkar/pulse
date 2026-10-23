package app.pulse.feature.connect

import android.Manifest
import android.os.Build

/**
 * Runtime permissions the Connect path asks for on Android 12 through 15.
 *
 * Location is intentionally absent. [Scan] is declared with `neverForLocation`,
 * so the platform does not require a location grant to scan for the strap.
 */
enum class LinkPermission(val androidPermission: String) {
    Scan(Manifest.permission.BLUETOOTH_SCAN),
    Connect(Manifest.permission.BLUETOOTH_CONNECT),
    Notifications(Manifest.permission.POST_NOTIFICATIONS),
    ;

    companion object {
        /**
         * Scan and connect are runtime permissions from API 31.
         * Notifications become a runtime permission on API 33.
         */
        fun requiredFor(sdkInt: Int): List<LinkPermission> = buildList {
            add(Scan)
            add(Connect)
            if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
                add(Notifications)
            }
        }

        fun fromAndroidPermission(name: String): LinkPermission? =
            entries.firstOrNull { it.androidPermission == name }
    }
}
