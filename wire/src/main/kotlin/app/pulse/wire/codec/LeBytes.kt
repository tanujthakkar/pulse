package app.pulse.wire.codec

internal fun readU16Le(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

internal fun writeU16Le(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = (value and 0xFF).toByte()
    bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
}

internal fun readU32Le(bytes: ByteArray, offset: Int): Long =
    (bytes[offset].toLong() and 0xFF) or
        ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
        ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
        ((bytes[offset + 3].toLong() and 0xFF) shl 24)

internal fun writeU32Le(bytes: ByteArray, offset: Int, value: Long) {
    val bits = value and 0xFFFF_FFFFL
    bytes[offset] = (bits and 0xFF).toByte()
    bytes[offset + 1] = ((bits ushr 8) and 0xFF).toByte()
    bytes[offset + 2] = ((bits ushr 16) and 0xFF).toByte()
    bytes[offset + 3] = ((bits ushr 24) and 0xFF).toByte()
}
