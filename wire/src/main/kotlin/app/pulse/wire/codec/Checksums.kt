package app.pulse.wire.codec

/**
 * CRC-16/MODBUS over an explicit slice.
 *
 * Reflected polynomial `0xA001`, init `0xFFFF`, no final XOR. The stored form
 * is little-endian. Spec §4 covers bytes `[0, 6)` of a format-1 frame.
 */
object Crc16Modbus {
    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        require(offset >= 0 && length >= 0 && offset + length <= data.size)
        var crc = 0xFFFF
        var index = offset
        val end = offset + length
        while (index < end) {
            crc = crc xor (data[index].toInt() and 0xFF)
            var bit = 0
            while (bit < 8) {
                crc = if ((crc and 1) != 0) {
                    (crc ushr 1) xor 0xA001
                } else {
                    crc ushr 1
                }
                bit += 1
            }
            index += 1
        }
        return crc and 0xFFFF
    }
}

/**
 * CRC-32/ISO-HDLC, the zlib polynomial.
 *
 * Reflected polynomial `0xEDB88320`, init `0xFFFFFFFF`, final XOR `0xFFFFFFFF`.
 * The stored form is little-endian. Spec §4 covers the padded bytes `[8, length−4)`.
 */
object Crc32IsoHdlc {
    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Long {
        require(offset >= 0 && length >= 0 && offset + length <= data.size)
        val crc = java.util.zip.CRC32()
        if (length > 0) {
            crc.update(data, offset, length)
        }
        return crc.value and 0xFFFF_FFFFL
    }
}
