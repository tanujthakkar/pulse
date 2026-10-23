package app.pulse.ble

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class FrameTapTest {
    @Test
    fun labelsUseTheShortBluetoothIdOrTheFirstGroup() {
        assertEquals("fd4b0002", characteristicLabel(StrapGatt.COMMAND))
        assertEquals("fd4b0003", characteristicLabel(StrapGatt.RESPONSE_NOTIFY))
        assertEquals("fd4b0004", characteristicLabel(StrapGatt.EVENT_NOTIFY))
        assertEquals("fd4b0005", characteristicLabel(StrapGatt.DATA_NOTIFY))
        assertEquals("fd4b0007", characteristicLabel(StrapGatt.EXTRA_NOTIFY))
        assertEquals("2a37", characteristicLabel(StrapGatt.HEART_RATE_MEASUREMENT))
        assertEquals("2a19", characteristicLabel(StrapGatt.BATTERY_LEVEL))
        assertEquals("2a26", characteristicLabel(StrapGatt.FIRMWARE_REVISION))
        assertEquals("11500001", characteristicLabel(UUID.fromString("11500001-6215-11ee-8c99-0242ac120002")))
    }

    @Test
    fun writesAndNotificationsReachTheTapAndTheSessionCallbacks() {
        val seen = mutableListOf<Triple<Boolean, UUID, ByteArray>>()
        val fixture = SessionFixture(
            nowMillis = { 42L },
            frameTap = FrameTap { incoming, characteristic, value, atMillis ->
                assertEquals(42L, atMillis)
                seen += Triple(incoming, characteristic, value.copyOf())
            },
        )
        val payload = byteArrayOf(0x11, 0x22)
        fixture.session.open(STRAP_ADDRESS)
        fixture.session.write(StrapGatt.COMMAND, payload)
        fixture.openAndReady()
        assertEquals(1, seen.size)
        assertFalse(seen[0].first)
        assertEquals(StrapGatt.COMMAND, seen[0].second)
        assertArrayEquals(payload, seen[0].third)

        fixture.radio.succeedWrite()
        assertEquals(listOf(StrapGatt.COMMAND to 0), fixture.events.writeResults)

        val note = byteArrayOf(0x01, 0x02)
        fixture.radio.emit(StrapGatt.RESPONSE_NOTIFY, note)
        assertEquals(2, seen.size)
        assertTrue(seen[1].first)
        assertEquals(StrapGatt.RESPONSE_NOTIFY, seen[1].second)
        assertArrayEquals(note, seen[1].third)
        assertEquals(1, fixture.events.notifications.size)
        assertArrayEquals(note, fixture.events.notifications.single().second)
    }

    @Test
    fun aFailingTapDoesNotBlockTheWriteOrTheNotification() {
        val fixture = SessionFixture(frameTap = FrameTap { _, _, _, _ -> error("tap failed") })
        fixture.session.open(STRAP_ADDRESS)
        fixture.session.write(StrapGatt.COMMAND, byteArrayOf(1))
        fixture.openAndReady()
        assertEquals(1, fixture.radio.writes.size)
        fixture.radio.succeedWrite()
        fixture.radio.emit(StrapGatt.DATA_NOTIFY, byteArrayOf(9))
        assertEquals(1, fixture.events.writeResults.size)
        assertEquals(1, fixture.events.notifications.size)
        assertArrayEquals(byteArrayOf(9), fixture.events.notifications.single().second)
    }
}
