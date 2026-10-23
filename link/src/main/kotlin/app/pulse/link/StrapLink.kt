package app.pulse.link

import app.pulse.ble.BondBegin
import app.pulse.ble.CloseReason
import app.pulse.ble.DeviceBond
import app.pulse.ble.ScanFailure
import app.pulse.ble.StrapSighting
import java.util.ArrayDeque

/**
 * Connect and bond state machine.
 *
 * Scan with the service filter, bond through the OS when this phone does not
 * already hold the bond, then open one [app.pulse.ble.GattSession]. A refusal,
 * or bonding that is still unfinished after [StrapLinkLimits.PAIRING_HELP_AFTER_MS],
 * moves to [LinkPhase.PAIRING_HELP] and stops. A clean failure is retried once
 * ([StrapLinkLimits.BOND_ATTEMPTS], [StrapLinkLimits.CONNECT_ATTEMPTS]) and
 * then stops. Nothing here sends the client hello or reads heart rate.
 *
 * Transitions:
 * - Idle → Scanning on [start]
 * - Scanning → Connecting when this phone already holds the bond
 * - Scanning → Bonding when it does not
 * - Scanning → Failed on scan failure
 * - Scanning → Idle on [stop]
 * - Bonding → Connecting when the bond completes
 * - Bonding → Bonding for one transient failure
 * - Bonding → PairingHelp on refusal, an immediate refuse, or the deadline
 * - Bonding → Failed when the second transient attempt fails
 * - Bonding → Idle on [stop]
 * - Connecting → Linked when the session is ready
 * - Connecting → Connecting for one non-refusal failure
 * - Connecting → PairingHelp when the session fails encryption
 * - Connecting → Failed on the second failure
 * - Connecting → Idle on [stop]
 * - Linked → Scanning when the link drops
 * - Linked → Idle on [stop]
 * - PairingHelp → Scanning on [retry], or Idle on [stop]
 * - Failed → Scanning on [retry], or Idle on [stop]
 */
class StrapLink(
    private val ports: LinkPorts,
    private val clock: LinkClock,
    private val runOnState: (block: () -> Unit) -> Unit = { block -> block() },
    private val log: (String) -> Unit = {},
) {
    private val runner = StateRunner()
    private val listeners = mutableListOf<(LinkSnapshot) -> Unit>()
    private var deadline: LinkAlarm? = null
    private var bondAttempts = 0
    private var sessionAttempts = 0
    private var address: String? = null
    private var phase = LinkPhase.IDLE
    private var block = LinkBlock.NONE
    private var scanFailure: Int? = null

    @Volatile
    private var current = LinkSnapshot()

    fun snapshot(): LinkSnapshot = current

    fun watch(listener: (LinkSnapshot) -> Unit) {
        onState {
            listeners += listener
            listener(current)
        }
    }

    fun start() = onState {
        if (phase != LinkPhase.IDLE) return@onState
        resetBudget()
        beginScan()
    }

    fun stop() = onState {
        if (phase == LinkPhase.IDLE) return@onState
        cancelDeadline()
        resetBudget()
        phase = LinkPhase.IDLE
        block = LinkBlock.NONE
        releaseRadio()
        publish()
    }

    /** User-initiated. Ignored unless the machine is sitting on help or a failure. */
    fun retry() = onState {
        if (phase != LinkPhase.PAIRING_HELP && phase != LinkPhase.FAILED) return@onState
        resetBudget()
        beginScan()
    }

    fun onSighting(sighting: StrapSighting) = onState {
        if (phase != LinkPhase.SCANNING) return@onState
        if (sighting.address.isBlank()) return@onState
        address = sighting.address
        ports.stopScan()
        when (ports.bondOf(sighting.address)) {
            DeviceBond.PRESENT -> openSession()
            DeviceBond.BONDING -> waitForBond()
            DeviceBond.ABSENT -> startBond()
        }
    }

    fun onScanFailed(code: Int) = onState {
        if (phase != LinkPhase.SCANNING) return@onState
        scanFailure = code
        enterTerminal(LinkPhase.FAILED, LinkBlock.SCAN)
    }

    fun onBondChanged(address: String, bond: DeviceBond, refused: Boolean) = onState {
        if (phase != LinkPhase.BONDING || address != this.address) return@onState
        when {
            bond == DeviceBond.PRESENT -> openSession()
            refused -> enterTerminal(LinkPhase.PAIRING_HELP, LinkBlock.BOND)
            bond == DeviceBond.BONDING -> Unit
            bondAttempts < StrapLinkLimits.BOND_ATTEMPTS -> startBond()
            else -> enterTerminal(LinkPhase.FAILED, LinkBlock.BOND)
        }
    }

    fun onSessionReady() = onState {
        if (phase != LinkPhase.CONNECTING) return@onState
        cancelDeadline()
        bondAttempts = 0
        sessionAttempts = 0
        phase = LinkPhase.LINKED
        block = LinkBlock.NONE
        publish()
    }

    fun onSessionClosed(reason: CloseReason, status: Int) = onState {
        if (phase == LinkPhase.LINKED && reason != CloseReason.REQUESTED) {
            sessionAttempts = 0
            beginScan()
            return@onState
        }
        if (phase != LinkPhase.CONNECTING || reason == CloseReason.REQUESTED) return@onState
        if (SessionFault.otherCentralHoldsBond(status)) {
            enterTerminal(LinkPhase.PAIRING_HELP, LinkBlock.BOND)
            return@onState
        }
        if (sessionAttempts < StrapLinkLimits.CONNECT_ATTEMPTS) {
            openSession()
        } else {
            enterTerminal(LinkPhase.FAILED, LinkBlock.CONNECT)
        }
    }

    private fun beginScan() {
        phase = LinkPhase.SCANNING
        block = LinkBlock.NONE
        scanFailure = null
        publish()
        ports.startScan()
    }

    private fun waitForBond() {
        if (bondAttempts < 1) bondAttempts = 1
        phase = LinkPhase.BONDING
        block = LinkBlock.NONE
        armDeadline()
        publish()
    }

    private fun startBond() {
        if (bondAttempts >= StrapLinkLimits.BOND_ATTEMPTS) {
            enterTerminal(LinkPhase.FAILED, LinkBlock.BOND)
            return
        }
        val target = address ?: return
        bondAttempts += 1
        phase = LinkPhase.BONDING
        block = LinkBlock.NONE
        armDeadline()
        publish()
        when (ports.beginBond(target)) {
            BondBegin.STARTED -> Unit
            BondBegin.ALREADY_PRESENT -> openSession()
            BondBegin.REFUSED -> enterTerminal(LinkPhase.PAIRING_HELP, LinkBlock.BOND)
            BondBegin.NOT_STARTED -> startBond()
        }
    }

    private fun openSession() {
        val target = address ?: return
        if (sessionAttempts >= StrapLinkLimits.CONNECT_ATTEMPTS) {
            enterTerminal(LinkPhase.FAILED, LinkBlock.CONNECT)
            return
        }
        sessionAttempts += 1
        phase = LinkPhase.CONNECTING
        block = LinkBlock.NONE
        publish()
        ports.openSession(target)
    }

    private fun enterTerminal(next: LinkPhase, why: LinkBlock) {
        cancelDeadline()
        phase = next
        block = why
        releaseRadio()
        publish()
    }

    private fun releaseRadio() {
        ports.stopScan()
        ports.cancelBond()
        ports.closeSession()
    }

    private fun resetBudget() {
        bondAttempts = 0
        sessionAttempts = 0
        address = null
        scanFailure = null
    }

    private fun armDeadline() {
        if (deadline != null) return
        deadline = clock.after(StrapLinkLimits.PAIRING_HELP_AFTER_MS) {
            onState {
                if (phase == LinkPhase.BONDING) {
                    enterTerminal(LinkPhase.PAIRING_HELP, LinkBlock.BOND)
                }
            }
        }
    }

    private fun cancelDeadline() {
        deadline?.cancel()
        deadline = null
    }

    private fun publish() {
        current = LinkSnapshot(
            phase = phase,
            address = address,
            bondAttempt = bondAttempts,
            connectAttempt = sessionAttempts,
            block = block,
            scanFailure = scanFailure,
            bluetoothOff = scanFailure == ScanFailure.ADAPTER_OFF,
        )
        log(
            "link ${current.phase} bond=${current.bondAttempt} " +
                "connect=${current.connectAttempt} address=${current.address}",
        )
        listeners.toList().forEach { it(current) }
    }

    private fun onState(block: () -> Unit) {
        runOnState { runner.execute(block) }
    }
}

/**
 * Runs work one task at a time. A task that submits more work does not
 * re-enter; the new work runs after the current task returns.
 */
private class StateRunner {
    private val queue = ArrayDeque<() -> Unit>()
    private var draining = false

    fun execute(task: () -> Unit) {
        queue.add(task)
        if (draining) return
        draining = true
        try {
            while (queue.isNotEmpty()) {
                queue.removeFirst().invoke()
            }
        } finally {
            draining = false
        }
    }
}
