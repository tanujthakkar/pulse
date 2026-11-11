package app.pulse.ble

import android.content.Context
import java.util.UUID

internal class SelectingScanPort(context: Context) : ScanPort {
    private val app = context.applicationContext
    private val real = AndroidScanPort(app)
    private val sim = SimulatedScanPort()
    private var simulated = false

    override fun start(
        service: UUID,
        onSighting: (StrapSighting) -> Unit,
        onFailed: (Int) -> Unit,
    ) {
        simulated = SimulatedStrap.isEnabled(app)
        if (simulated) sim.start(service, onSighting, onFailed) else real.start(service, onSighting, onFailed)
    }

    override fun stop() {
        if (simulated) sim.stop() else real.stop()
    }
}

internal class SelectingBondPort(context: Context) : BondPort {
    private val app = context.applicationContext
    private val real = AndroidBondPort(app)

    override var onUpdate: ((address: String, bond: DeviceBond, refused: Boolean) -> Unit)?
        get() = real.onUpdate
        set(value) {
            real.onUpdate = value
        }

    override fun state(address: String): DeviceBond {
        if (SimulatedStrap.isEnabled(app) && address == SimulatedStrap.ADDRESS) return DeviceBond.PRESENT
        return real.state(address)
    }

    override fun createBond(address: String): Boolean {
        if (SimulatedStrap.isEnabled(app)) return false
        return real.createBond(address)
    }

    override fun cancel(address: String) {
        if (!SimulatedStrap.isEnabled(app)) real.cancel(address)
    }
}

internal class SimulatedScanPort : ScanPort {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var generation = 0

    override fun start(
        service: UUID,
        onSighting: (StrapSighting) -> Unit,
        onFailed: (Int) -> Unit,
    ) {
        val ticket = ++generation
        handler.post {
            if (ticket != generation) return@post
            onSighting(
                StrapSighting(
                    address = SimulatedStrap.ADDRESS,
                    rssi = -55,
                    advertisedName = SimulatedStrap.ADVERTISED_NAME,
                ),
            )
        }
    }

    override fun stop() {
        generation += 1
    }
}
