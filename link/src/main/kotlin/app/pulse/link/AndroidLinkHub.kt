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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Wires [StrapLink] to the platform scanner, bond port, and one [GattSession].
 *
 * The session is opened on the first sighting so Heart Rate Measurement can
 * notify before bonding finishes. A later [openSession] reuses that client.
 * The hello is not written. Samples stay in [heartRate] and are dropped when
 * the session closes.
 */
private class SessionSlot {
    var session: GattSession? = null
}

internal class AndroidLinkHub(
    private val context: Context,
    private val heartRate: LiveHeartRateFeed,
) : LinkPorts {
    private var link: StrapLink? = null
    private var session: GattSession? = null
    private var sessionAddress: String? = null
    private var feedGeneration = 0
    private var heartRateJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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

    override fun watchHeartRate(address: String) {
        ensureSession(address)
    }

    override fun openSession(address: String) {
        when (sessionReuse(session?.phase, session != null && sessionAddress == address)) {
            SessionReuse.REUSE -> session?.subscribeStrapNotifies()
            SessionReuse.REUSE_AND_READY -> {
                session?.subscribeStrapNotifies()
                link?.onSessionReady()
            }
            SessionReuse.REPLACE -> {
                ensureSession(address)
                session?.subscribeStrapNotifies()
            }
        }
    }

    override fun closeSession() {
        dropFeed()
        val current = session ?: return
        session = null
        sessionAddress = null
        current.disconnect()
    }

    private fun ensureSession(address: String) {
        when (sessionReuse(session?.phase, session != null && sessionAddress == address)) {
            SessionReuse.REUSE, SessionReuse.REUSE_AND_READY -> return
            SessionReuse.REPLACE -> Unit
        }
        dropFeed()
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
                    sessionAddress = null
                    dropFeed()
                    link?.onSessionClosed(reason, active.terminalStatus ?: 0)
                }
            },
            deferProprietaryNotifies = true,
        )
        opened.session = created
        session = created
        sessionAddress = address
        follow(created)
        previous?.disconnect()
        created.open(address)
    }

    private fun follow(created: GattSession) {
        val generation = ++feedGeneration
        heartRateJob?.cancel()
        heartRateJob = scope.launch {
            created.heartRate.collect { sample ->
                if (generation == feedGeneration) {
                    heartRate.publish(sample)
                }
            }
        }
    }

    private fun dropFeed() {
        feedGeneration += 1
        heartRateJob?.cancel()
        heartRateJob = null
        heartRate.clear()
    }
}

/** The process-wide link and the in-memory heart-rate feed it fills. */
class LinkAssembly(
    val strapLink: StrapLink,
    val heartRate: LiveHeartRateFeed,
)

object StrapLinks {
    fun create(context: Context): LinkAssembly {
        val appContext = context.applicationContext
        val heartRate = LiveHeartRateFeed()
        val hub = AndroidLinkHub(appContext, heartRate)
        val link = StrapLink(
            ports = hub,
            clock = SystemLinkClock(),
            runOnState = mainExecutor(),
            log = { line -> Log.i(BleClients.LOG_TAG, line) },
        )
        hub.attach(link)
        return LinkAssembly(link, heartRate)
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
