package app.pulse.ble

internal class ManualAlarmClock : AlarmClock {
    private val pending = mutableListOf<() -> Unit>()
    var closed: Boolean = false
        private set

    override fun after(delayMs: Long, task: () -> Unit): Alarm {
        pending += task
        return Alarm { pending.remove(task) }
    }

    fun fire() {
        val due = pending.toList()
        pending.clear()
        due.forEach { it() }
    }

    override fun close() {
        closed = true
        pending.clear()
    }
}
