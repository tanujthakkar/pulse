package app.pulse

/** Destinations owned by the app shell. Feature modules supply the screens. */
enum class PulseRoute(val route: String, val label: String, val inBar: Boolean) {
    Home("home", "Home", true),
    Days("days", "Days", true),
    Sync("sync", "Sync", true),
    Live("live", "Live", true),
    More("more", "More", true),
    Connect("connect", "Connect", false),
    Debug("debug", "Debug", false),
    ;

    /** One-letter marks collide for Days and Debug, so Days uses two letters. */
    val mark: String
        get() = if (this == Days) "Dy" else label.take(1)
}

/** Import is opened from More. It is not a bottom-bar destination. */
const val IMPORT_ROUTE: String = "import"
