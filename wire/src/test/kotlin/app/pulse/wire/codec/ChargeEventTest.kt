package app.pulse.wire.codec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ChargeEventTest {
    @Test
    fun eventsSevenAndEightAreChargingAndAPercentIsNot() {
        assertEquals(true, ChargeEvent.charging(event(ChargeEvent.ON)))
        assertEquals(false, ChargeEvent.charging(event(ChargeEvent.OFF)))
        assertNull(ChargeEvent.charging(event(3)))
        val battery = FrameCodec.encodeCommandResponse(
            sequence = 1,
            command = BatteryCommand.NUMBER,
            originSequence = 1,
            result = CommandResult.SUCCESS,
            body = byteArrayOf(64, 0, 0, 0),
        )
        val decoded = FrameCodec.decode(battery) as DecodeResult.Ok
        assertNull(ChargeEvent.charging(decoded.frame))
    }

    private fun event(number: Int): WireFrame {
        val bytes = FrameCodec.encode(
            headerWord = HeaderWord.COMMON_RESPONSE,
            packetType = PacketType.EVENT,
            sequence = 0,
            command = number,
            body = ByteArray(0),
        )
        return (FrameCodec.decode(bytes) as DecodeResult.Ok).frame
    }
}
