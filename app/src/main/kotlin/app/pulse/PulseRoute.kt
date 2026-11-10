package app.pulse

/** Destinations owned by the app shell. Days, Sync, and Live are not in the shell. */
enum class PulseRoute(val route: String, val label: String) {
    Home("home", "Home"),
    More("more", "More"),
    Connect("connect", "Connect"),
    Debug("debug", "Debug"),
}

/** Import is opened from More. */
const val IMPORT_ROUTE: String = "import"
