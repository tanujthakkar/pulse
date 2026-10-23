package app.pulse.ble

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import java.util.UUID

internal class RecordingEvents : SessionEvents {
    var readyMtu: Int? = null
    var writeVisibleAtReady: Boolean? = null
    var radio: FakeGattRadio? = null
    val notifications = mutableListOf<Pair<UUID, ByteArray>>()
    val closed = mutableListOf<CloseReason>()
    val writeResults = mutableListOf<Pair<UUID, Int>>()

    override fun onReady(mtu: Int) {
        readyMtu = mtu
        writeVisibleAtReady = radio?.writes?.isNotEmpty() == true
    }

    override fun onNotification(characteristic: UUID, value: ByteArray) {
        notifications += characteristic to value.copyOf()
    }

    override fun onWriteFinished(characteristic: UUID, status: Int) {
        writeResults += characteristic to status
    }

    override fun onClosed(reason: CloseReason) {
        closed += reason
    }
}

internal class SessionFixture {
    val radio = FakeGattRadio()
    val clock = ManualAlarmClock()
    val events = RecordingEvents()
    val session = GattSession(radio, events, clock)

    init {
        events.radio = radio
    }
}

internal const val STRAP_ADDRESS: String = "AA:BB:CC:DD:EE:FF"

internal fun SessionFixture.openAndReady(mtu: Int = StrapGatt.REQUESTED_MTU) {
    session.open(STRAP_ADDRESS)
    radio.succeedConnect()
    radio.succeedDiscover(
        services = setOf(StrapGatt.SERVICE),
        characteristics = StrapGatt.NOTIFY.toSet() + StrapGatt.COMMAND,
    )
    radio.succeedMtu(mtu)
    repeat(StrapGatt.NOTIFY.size) {
        radio.succeedCccd()
    }
}

internal fun assertSerialized(lines: List<String>) {
    var open = false
    var openName: String? = null
    for (line in lines) {
        when {
            line.startsWith("BEGIN ") -> {
                assertFalse(open, "overlap at '$line' in $lines")
                open = true
                openName = line.removePrefix("BEGIN ")
            }
            line.startsWith("END ") -> {
                assertTrue(open, "END without BEGIN at '$line' in $lines")
                val endName = line.removePrefix("END ").substringBefore(" status=")
                assertEquals(openName, endName, lines.toString())
                open = false
            }
        }
    }
    assertFalse(open, "unclosed op in $lines")
}
