package app.pulse.ble

import java.util.UUID

/**
 * A strap found by a scan. [advertisedName] is not an identity: project rule 4
 * says the scan filter is the proprietary service, not the advertised name.
 */
data class StrapSighting(
    val address: String,
    val rssi: Int,
    val advertisedName: String?,
)

interface ScanEvents {
    fun onSighting(sighting: StrapSighting) {}

    fun onScanFailed(code: Int) {}
}

object ScanFailure {
    const val ADAPTER_OFF: Int = -1
    const val PERMISSION: Int = -2
}

internal interface ScanPort {
    fun start(
        service: UUID,
        onSighting: (StrapSighting) -> Unit,
        onFailed: (Int) -> Unit,
    )

    fun stop()
}

/**
 * Scan filtered by [StrapGatt.SERVICE].
 *
 * [start] and [stop] are serialized with the result callbacks. Call [stop]
 * when the screen closes so the platform scan is not left running. Whether a
 * real strap shows up within 10 seconds is a hardware check; see
 * `docs/hardware-acceptance.md`.
 */
class StrapScanner internal constructor(
    private val port: ScanPort,
    private val events: ScanEvents,
) {
    private val runner = SerialRunner()
    private var scanning = false

    fun start() {
        runner.execute {
            if (scanning) port.stop()
            scanning = true
            port.start(StrapGatt.SERVICE, ::deliver, ::fail)
        }
    }

    fun stop() {
        runner.execute {
            if (!scanning) return@execute
            scanning = false
            port.stop()
        }
    }

    private fun deliver(sighting: StrapSighting) {
        runner.execute {
            if (!scanning) return@execute
            events.onSighting(sighting)
        }
    }

    private fun fail(code: Int) {
        runner.execute {
            if (!scanning) return@execute
            scanning = false
            port.stop()
            events.onScanFailed(code)
        }
    }
}
