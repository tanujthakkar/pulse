package app.pulse.ble

import java.time.LocalDate
import java.time.ZoneId

/**
 * The in-process strap, without a GATT radio.
 *
 * History sync drives [onCommand] with the same frames the command
 * characteristic would carry, then [pull] for data notifications. [NORMAL]
 * serves several days. This is the debug script the radio uses.
 */
class SimulatedStrapScript(
    scenario: StrapScenario,
    today: LocalDate = LocalDate.now(ZoneId.systemDefault()),
    zone: ZoneId = ZoneId.systemDefault(),
    clockSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
) {
    private val engine = SimulatedStrapEngine(scenario, today, zone, clockSeconds)

    fun onCommand(frame: ByteArray): List<ByteArray> = engine.onCommand(frame)

    fun consumeArm(): Boolean = engine.consumeArm()

    fun pull(maxFrames: Int): SimulatedPull = when (val pull = engine.pull(maxFrames)) {
        is StreamPull.Frames -> SimulatedPull.Frames(pull.frames)
        is StreamPull.Disconnect -> SimulatedPull.Disconnect(pull.frames)
        StreamPull.Done -> SimulatedPull.Done
    }
}

sealed class SimulatedPull {
    class Frames(val frames: List<ByteArray>) : SimulatedPull()

    class Disconnect(val frames: List<ByteArray>) : SimulatedPull()

    data object Done : SimulatedPull()
}
