package app.pulse.link

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.pulse.ble.BleClients
import app.pulse.ble.BondEvents
import app.pulse.ble.CloseReason
import app.pulse.ble.DeviceBond
import app.pulse.ble.GattSession
import app.pulse.ble.ScanEvents
import app.pulse.ble.SessionEvents
import app.pulse.ble.StrapSighting

/**
 * Wires [StrapLink] to the platform scanner, bond port, and a new
 * [GattSession] per open. The hello is not written.
 */
private class SessionSlot {
    var session: GattSession? = null
}

internal class AndroidLinkHub(
    private val context: Context,
) : LinkPorts {
    private var link: StrapLink? = null
    private var session: GattSession? = null

    private val scanner = BleClients.strapScanner(
        context,
        object : ScanEvents {
            override fun onSighting(sighting: StrapSighting) {
                link?.onSighting(sighting)
            }

            override fun onScanFailed(code: Int) {
                link?.onScanFailed(code)
            }
        },
    )

    private val bonds = BleClients.bondController(
        context,
        object : BondEvents {
            override fun onChanged(address: String, bond: DeviceBond, refused: Boolean) {
                link?.onBondChanged(address, bond, refused)
            }
        },
    )

    fun attach(owner: StrapLink) {
        link = owner
    }

    override fun startScan() {
        scanner.start()
    }

    override fun stopScan() {
        scanner.stop()
    }

    override fun bondOf(address: String): DeviceBond = bonds.state(address)

    override fun beginBond(address: String) = bonds.begin(address)

    override fun cancelBond() {
        bonds.cancel()
    }

    override fun openSession(address: String) {
        val previous = session
        val opened = SessionSlot()
        val created = BleClients.gattSession(
            context,
            object : SessionEvents {
                override fun onReady(mtu: Int) {
                    val active = opened.session ?: return
                    if (session !== active) return
                    link?.onSessionReady()
                }

                override fun onClosed(reason: CloseReason) {
                    val active = opened.session ?: return
                    if (session !== active) return
                    session = null
                    link?.onSessionClosed(reason, active.terminalStatus ?: 0)
                }
            },
        )
        opened.session = created
        session = created
        previous?.disconnect()
        created.open(address)
    }

    override fun closeSession() {
        val current = session ?: return
        session = null
        current.disconnect()
    }
}

object StrapLinks {
    fun create(context: Context): StrapLink {
        val appContext = context.applicationContext
        val hub = AndroidLinkHub(appContext)
        val link = StrapLink(
            ports = hub,
            clock = SystemLinkClock(),
            runOnState = mainExecutor(),
            log = { line -> Log.i(BleClients.LOG_TAG, line) },
        )
        hub.attach(link)
        return link
    }

    private fun mainExecutor(): (block: () -> Unit) -> Unit {
        val handler = Handler(Looper.getMainLooper())
        return { block ->
            if (Looper.myLooper() == Looper.getMainLooper()) {
                block()
            } else {
                handler.post(block)
            }
        }
    }
}
