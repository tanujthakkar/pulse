package app.pulse

/** Destinations owned by the app shell. Feature modules supply the screens. */
enum class PulseRoute(val route: String, val label: String) {
    Connect("connect", "Connect"),
    Live("live", "Live"),
    Sync("sync", "Sync"),
    Debug("debug", "Debug"),
}
