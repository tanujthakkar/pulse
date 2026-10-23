package app.pulse.ble

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StrapGattTest {
    @Test
    fun uuidsMatchTheSpec() {
        assertEquals("fd4b0001-cce1-4033-93ce-002d5875f58a", StrapGatt.SERVICE.toString())
        assertEquals("fd4b0002-cce1-4033-93ce-002d5875f58a", StrapGatt.COMMAND.toString())
        assertEquals(
            listOf(
                "fd4b0003-cce1-4033-93ce-002d5875f58a",
                "fd4b0004-cce1-4033-93ce-002d5875f58a",
                "fd4b0005-cce1-4033-93ce-002d5875f58a",
                "fd4b0007-cce1-4033-93ce-002d5875f58a",
            ),
            StrapGatt.NOTIFY.map { it.toString() },
        )
        assertEquals("00002902-0000-1000-8000-00805f9b34fb", StrapGatt.CCCD.toString())
        assertArrayEquals(byteArrayOf(0x01, 0x00), StrapGatt.ENABLE_NOTIFICATION)
        assertEquals(247, StrapGatt.REQUESTED_MTU)
    }
}
