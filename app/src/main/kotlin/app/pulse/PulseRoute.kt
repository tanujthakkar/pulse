package app.pulse

/** Destinations owned by the app shell. Feature modules supply the screens. */
enum class PulseRoute(val route: String, val label: String) {
    Connect("connect", "Connect"),
    Live("live", "Live"),
    Sync("sync", "Sync"),
    Days("days", "Days"),
    Debug("debug", "Debug"),
    ;

    /** One-letter marks collide for Days and Debug, so Days uses two letters. */
    val mark: String
        get() = if (this == Days) "Dy" else label.take(1)
}

/** Import is opened from Debug. It is not a bottom-bar destination. */
const val IMPORT_ROUTE: String = "import"
