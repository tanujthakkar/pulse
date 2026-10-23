package app.pulse.wire.codec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ChecksumsTest {
    @Test
    fun modbusCheckValueMatchesTheCatalogue() {
        assertEquals(0x4B37, Crc16Modbus.compute("123456789".encodeToByteArray()))
    }

    @Test
    fun modbusHelloHeaderMatchesTheSpec() {
        val header = hexBytes("AA 01 08 00 00 01")
        assertEquals(0x71E6, Crc16Modbus.compute(header))
    }

    @Test
    fun modbusRespectsOffsetAndRejectsAnEmptyRangeAsTheInitValue() {
        val header = hexBytes("00 AA 01 08 00 00 01")
        assertEquals(0x71E6, Crc16Modbus.compute(header, offset = 1, length = 6))
        assertEquals(0xFFFF, Crc16Modbus.compute(header, offset = 1, length = 0))
        assertThrows(IllegalArgumentException::class.java) {
            Crc16Modbus.compute(header, offset = -1, length = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Crc16Modbus.compute(header, offset = 0, length = header.size + 1)
        }
    }

    @Test
    fun isoHdlcCheckValueMatchesTheCatalogue() {
        assertEquals(0xCBF43926L, Crc32IsoHdlc.compute("123456789".encodeToByteArray()))
    }

    @Test
    fun isoHdlcHelloPayloadMatchesTheSpec() {
        assertEquals(0x8D5C3E36L, Crc32IsoHdlc.compute(hexBytes("23 01 91 01")))
    }

    @Test
    fun isoHdlcRespectsOffsetAndAnEmptySlice() {
        val payload = hexBytes("FF 23 01 91 01")
        assertEquals(0x8D5C3E36L, Crc32IsoHdlc.compute(payload, offset = 1, length = 4))
        assertEquals(0L, Crc32IsoHdlc.compute(payload, offset = 1, length = 0))
        assertThrows(IllegalArgumentException::class.java) {
            Crc32IsoHdlc.compute(payload, offset = 2, length = -1)
        }
    }
}
