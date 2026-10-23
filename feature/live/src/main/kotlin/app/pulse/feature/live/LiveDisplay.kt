package app.pulse.feature.live

import app.pulse.ble.LiveHeartRate
import app.pulse.ble.SensorContact
import app.pulse.link.LinkPhase

/** How many recent in-range readings the sparkline keeps. Not persisted. */
const val LIVE_TRACE_LENGTH: Int = 30

/**
 * Project rule 14. Readings outside this window stay on the sample and are
 * not drawn. Milestone 1 does not store them.
 */
const val LIVE_BPM_MIN: Int = 25
const val LIVE_BPM_MAX: Int = 230

const val LIVE_BPM_PLACEHOLDER: String = "—"

fun displayedBpm(bpm: Int): Int? = if (bpm in LIVE_BPM_MIN..LIVE_BPM_MAX) bpm else null

fun appendTrace(trace: List<Int>, displayed: Int?): List<Int> {
    if (displayed == null) return trace
    val next = trace + displayed
    return if (next.size <= LIVE_TRACE_LENGTH) next else next.takeLast(LIVE_TRACE_LENGTH)
}

fun formatRr(rrMs: List<Int>): String {
    if (rrMs.isEmpty()) return LIVE_BPM_PLACEHOLDER
    return rrMs.joinToString(separator = ", ") { "$it ms" }
}

data class LiveUi(
    val status: String,
    val bpm: String,
    val rr: String,
    val contact: String?,
    val sparkline: List<Int>,
)

fun liveUi(phase: LinkPhase, sample: LiveHeartRate?, trace: List<Int>): LiveUi {
    val shown = sample?.bpm?.let(::displayedBpm)
    return LiveUi(
        status = statusFor(phase),
        bpm = shown?.toString() ?: LIVE_BPM_PLACEHOLDER,
        rr = sample?.let { formatRr(it.rrMs) } ?: LIVE_BPM_PLACEHOLDER,
        contact = sample?.let { contactLabel(it.contact) },
        sparkline = trace,
    )
}

private fun contactLabel(contact: SensorContact): String? = when (contact) {
    SensorContact.DETECTED -> "Skin contact"
    SensorContact.NOT_DETECTED -> "No skin contact"
    SensorContact.UNSUPPORTED -> null
}

private fun statusFor(phase: LinkPhase): String = when (phase) {
    LinkPhase.IDLE -> "Open Connect and start a search."
    LinkPhase.SCANNING -> "Looking for a strap."
    LinkPhase.BONDING -> "Bonding. Heart rate can show before that finishes."
    LinkPhase.CONNECTING -> "Connecting."
    LinkPhase.LINKED -> "Connected."
    LinkPhase.PAIRING_HELP -> "The strap is still bonded to another phone."
    LinkPhase.FAILED -> "The link stopped."
}
