package app.pulse.link

import app.pulse.wire.codec.BatteryCommand
import app.pulse.wire.codec.ChargeEvent
import app.pulse.wire.codec.CommandResult
import app.pulse.wire.codec.FrameCodec
import app.pulse.wire.codec.HeaderWord
import app.pulse.wire.codec.PacketType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChargeSignalsTest {
    @Test
    fun eventsSevenAndEightUpdateChargingOffTheCaller() {
        val signals = ChargeSignals()
        signals.offer(event(ChargeEvent.ON))
        signals.awaitIdle()
        assertEquals(true, signals.charging.value)
        signals.offer(event(3))
        signals.awaitIdle()
        assertEquals(true, signals.charging.value)
        val battery = FrameCodec.encodeCommandResponse(
            sequence = 1,
            command = BatteryCommand.NUMBER,
            originSequence = 1,
            result = CommandResult.SUCCESS,
            body = byteArrayOf(64, 0, 0, 0),
        )
        signals.offer(battery)
        signals.awaitIdle()
        assertEquals(true, signals.charging.value)
        signals.offer(event(ChargeEvent.OFF))
        signals.awaitIdle()
        assertEquals(false, signals.charging.value)
        signals.clear()
        signals.awaitIdle()
        assertNull(signals.charging.value)
    }

    private fun event(number: Int): ByteArray = FrameCodec.encode(
        headerWord = HeaderWord.COMMON_RESPONSE,
        packetType = PacketType.EVENT,
        sequence = 0,
        command = number,
        body = ByteArray(0),
    )
}
