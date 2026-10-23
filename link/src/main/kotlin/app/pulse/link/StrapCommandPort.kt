package app.pulse.link

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Command writes and notifications for a manual history sync.
 *
 * [address] is set after the client hello succeeds and cleared when the
 * session drops. [write] is write-with-response on the command characteristic.
 * This port does not choose which commands are legal.
 */
class StrapCommandPort {
    @Volatile
    var address: String? = null
        private set

    private var writer: ((ByteArray) -> Unit)? = null
    private val listeners = CopyOnWriteArrayList<Listener>()

    fun write(frame: ByteArray): Boolean {
        val sink = writer ?: return false
        sink(frame)
        return true
    }

    fun listen(
        onNotification: (source: String, bytes: ByteArray) -> Unit,
        onDown: () -> Unit,
    ): () -> Unit {
        val listener = Listener(onNotification, onDown)
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    internal fun bind(write: (ByteArray) -> Unit) {
        writer = write
    }

    internal fun markReady(address: String) {
        this.address = address
    }

    internal fun markUnready() {
        address = null
    }

    internal fun markDown() {
        address = null
        writer = null
        val current = listeners.toList()
        current.forEach { it.onDown() }
    }

    internal fun deliver(source: String, bytes: ByteArray) {
        val copy = bytes.copyOf()
        listeners.forEach { it.onNotification(source, copy) }
    }

    private class Listener(
        val onNotification: (String, ByteArray) -> Unit,
        val onDown: () -> Unit,
    )
}
