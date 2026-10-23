package app.pulse.wire.codec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf

internal fun hexBytes(text: String): ByteArray {
    val compact = text.trim().split(Regex("\\s+")).joinToString("")
    require(compact.length % 2 == 0) { compact }
    return ByteArray(compact.length / 2) { index ->
        compact.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

internal fun ByteArray.toHex(): String =
    joinToString("") { "%02X".format(it.toInt() and 0xFF) }

internal fun DecodeResult.ok(): WireFrame =
    assertInstanceOf(DecodeResult.Ok::class.java, this).frame

internal fun DecodeResult.rejected(): RejectReason =
    assertInstanceOf(DecodeResult.Rejected::class.java, this).reason

internal fun List<AccumulatorEvent>.frames(): List<WireFrame> =
    mapNotNull { (it as? AccumulatorEvent.Frame)?.frame }

internal fun assertReject(reason: RejectReason, events: List<AccumulatorEvent>) {
    val reasons = events.mapNotNull { (it as? AccumulatorEvent.Rejected)?.reason }
    assertEquals(emptyList<WireFrame>(), events.frames())
    org.junit.jupiter.api.Assertions.assertTrue(reasons.contains(reason)) { reasons.toString() }
}
