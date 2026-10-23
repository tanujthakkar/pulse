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
        assertEquals("0000180d-0000-1000-8000-00805f9b34fb", StrapGatt.HEART_RATE_SERVICE.toString())
        assertEquals(
            "00002a37-0000-1000-8000-00805f9b34fb",
            StrapGatt.HEART_RATE_MEASUREMENT.toString(),
        )
        assertEquals("0000180f-0000-1000-8000-00805f9b34fb", StrapGatt.BATTERY_SERVICE.toString())
        assertEquals("00002a19-0000-1000-8000-00805f9b34fb", StrapGatt.BATTERY_LEVEL.toString())
        assertEquals("0000180a-0000-1000-8000-00805f9b34fb", StrapGatt.DEVICE_INFO_SERVICE.toString())
        assertEquals("00002a26-0000-1000-8000-00805f9b34fb", StrapGatt.FIRMWARE_REVISION.toString())
        assertEquals("00002902-0000-1000-8000-00805f9b34fb", StrapGatt.CCCD.toString())
        assertArrayEquals(byteArrayOf(0x01, 0x00), StrapGatt.ENABLE_NOTIFICATION)
        assertEquals(247, StrapGatt.REQUESTED_MTU)
    }
}
