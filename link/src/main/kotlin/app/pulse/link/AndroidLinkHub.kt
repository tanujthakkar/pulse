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
import app.pulse.ble.StrapGatt
import app.pulse.ble.StrapSighting
import app.pulse.data.DeviceDirectory
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Wires [StrapLink] to the platform scanner, bond port, and one [GattSession].
 *
 * The session is opened on the first sighting so Heart Rate Measurement can
 * notify before bonding finishes. A later [openSession] reuses that client
 * and subscribes the proprietary notifies. When those descriptors are on,
 * [StrapHandshake] writes the client hello. Samples stay in [heartRate] and
 * are dropped when the session closes. A failed hello leaves the session up.
 */
private class SessionSlot {
    var session: GattSession? = null
}

internal class AndroidLinkHub(
    private val context: Context,
    private val heartRate: LiveHeartRateFeed,
    private val devices: DeviceDirectory,
    private val commands: StrapCommandPort,
) : LinkPorts {
    private var link: StrapLink? = null
    private var session: GattSession? = null
    private var sessionAddress: String? = null
    private var handshake: StrapHandshake? = null
    private val handshakeClock = SystemLinkClock()
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
        commands.markDown()
        dropFeed()
        handshake?.stop()
        handshake = null
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
        commands.markDown()
        dropFeed()
        handshake?.stop()
        handshake = null
        val previous = session
        val opened = SessionSlot()
        val created = BleClients.gattSession(
            context,
            object : SessionEvents {
                override fun onReady(mtu: Int) {
                    val active = opened.session ?: return
                    if (session !== active) return
                    link?.onSessionReady()
                    startHandshake(active)
                }

                override fun onNotification(characteristic: UUID, value: ByteArray) {
                    val active = opened.session ?: return
                    if (session !== active) return
                    handshake?.onNotification(characteristic, value)
                    commands.deliver(characteristic.toString(), value)
                }

                override fun onWriteFinished(characteristic: UUID, status: Int) {
                    val active = opened.session ?: return
                    if (session !== active) return
                    handshake?.onWriteFinished(characteristic, status)
                }

                override fun onReadFinished(characteristic: UUID, status: Int, value: ByteArray) {
                    val active = opened.session ?: return
                    if (session !== active) return
                    handshake?.onRead(characteristic, status, value)
                }

                override fun onClosed(reason: CloseReason) {
                    val active = opened.session ?: return
                    if (session !== active) return
                    commands.markDown()
                    handshake?.stop()
                    handshake = null
                    session = null
                    sessionAddress = null
                    dropFeed()
                    link?.onSessionClosed(
                        reason,
                        active.terminalStatus ?: 0,
                        active.proprietaryUnbonded,
                    )
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

    private fun startHandshake(active: GattSession) {
        if (handshake != null) return
        val target = sessionAddress ?: return
        commands.bind { bytes -> active.write(StrapGatt.COMMAND, bytes, withResponse = true) }
        val created = StrapHandshake(
            address = target,
            knows = active::knows,
            write = { bytes -> active.write(StrapGatt.COMMAND, bytes, withResponse = true) },
            read = active::read,
            clock = handshakeClock,
            devices = devices,
            heartRateLive = {
                active.heartRateChannelUp || active.heartRate.replayCache.isNotEmpty()
            },
            report = { update ->
                if (update.settled) {
                    if (update.succeeded) commands.markReady(target) else commands.markUnready()
                }
                link?.onDeviceReport(update)
            },
            log = { line -> Log.i(BleClients.LOG_TAG, line) },
        )
        handshake = created
        created.start()
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
    val commands: StrapCommandPort,
)

object StrapLinks {
    fun create(context: Context, devices: DeviceDirectory): LinkAssembly {
        val appContext = context.applicationContext
        val heartRate = LiveHeartRateFeed()
        val commands = StrapCommandPort()
        val hub = AndroidLinkHub(appContext, heartRate, devices, commands)
        val link = StrapLink(
            ports = hub,
            clock = SystemLinkClock(),
            runOnState = mainExecutor(),
            log = { line -> Log.i(BleClients.LOG_TAG, line) },
        )
        hub.attach(link)
        return LinkAssembly(link, heartRate, commands)
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
