package app.pulse.ble

/**
 * BEGIN/END trace for GATT operations.
 *
 * A second BEGIN before END, or an END with nothing open, counts as an overlap.
 * Notifications and [mark] lines are not operations.
 */
internal class GattOpLog {
    private val lock = Any()
    private val lines = mutableListOf<String>()
    private var open = false
    var overlapCount: Int = 0
        private set

    fun begin(name: String) {
        synchronized(lock) {
            if (open) overlapCount += 1
            open = true
            lines += "BEGIN $name"
        }
    }

    fun end(name: String, status: Int) {
        synchronized(lock) {
            if (!open) overlapCount += 1
            open = false
            lines += "END $name status=$status"
        }
    }

    fun mark(line: String) {
        synchronized(lock) {
            lines += line
        }
    }

    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }
}
