package app.pulse.link

import app.pulse.ble.FrameTap
import app.pulse.ble.StrapGatt
import app.pulse.ble.characteristicLabel
import app.pulse.wire.codec.FrameIntake
import java.util.UUID

/**
 * Records GATT payloads. This does not write, and it does not read the
 * history-ack gate. Format-1 traffic is the command characteristic and the
 * four notify characteristics. Other notifications, including Heart Rate
 * Measurement, stay unframed.
 */
internal fun sessionFrameTap(intake: FrameIntake): FrameTap {
    return FrameTap { incoming, characteristic, value, atMillis ->
        intake.offer(
            incoming = incoming,
            characteristic = characteristicLabel(characteristic),
            framed = framedCharacteristic(characteristic),
            payload = value,
            atMillis = atMillis,
        )
    }
}

internal fun framedCharacteristic(characteristic: UUID): Boolean {
    return characteristic == StrapGatt.COMMAND || characteristic in StrapGatt.NOTIFY
}
