package app.pulse.link

import app.pulse.ble.StrapGatt
import app.pulse.wire.codec.FrameIntake
import app.pulse.wire.codec.FrameRing
import app.pulse.wire.codec.FrameVerdict
import app.pulse.wire.codec.HelloCommand
import app.pulse.wire.codec.PacketType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionFrameTapTest {
    @Test
    fun commandAndNotifiesAreFramedAndHeartRateIsNot() {
        assertTrue(framedCharacteristic(StrapGatt.COMMAND))
        StrapGatt.NOTIFY.forEach { characteristic ->
            assertTrue(framedCharacteristic(characteristic))
        }
        assertFalse(framedCharacteristic(StrapGatt.HEART_RATE_MEASUREMENT))
        assertFalse(framedCharacteristic(StrapGatt.BATTERY_LEVEL))

        val ring = FrameRing()
        val tap = sessionFrameTap(FrameIntake(ring))
        tap.onPayload(false, StrapGatt.COMMAND, HelloCommand.bytes(), 10L)
        tap.onPayload(true, StrapGatt.HEART_RATE_MEASUREMENT, byteArrayOf(0x00, 0x3C), 11L)
        tap.onPayload(true, StrapGatt.RESPONSE_NOTIFY, HelloCommand.bytes(), 12L)
        val notes = ring.snapshot()
        assertEquals(3, notes.size)
        assertEquals("fd4b0003", notes[0].characteristic)
        assertEquals(PacketType.COMMAND, notes[0].packetType)
        assertEquals(FrameVerdict.OK, notes[0].crc)
        assertTrue(notes[0].incoming)
        assertEquals("2a37", notes[1].characteristic)
        assertEquals(FrameVerdict.NOT_A_FRAME, notes[1].crc)
        assertEquals("fd4b0002", notes[2].characteristic)
        assertEquals(PacketType.COMMAND, notes[2].packetType)
        assertFalse(notes[2].incoming)
    }
}
