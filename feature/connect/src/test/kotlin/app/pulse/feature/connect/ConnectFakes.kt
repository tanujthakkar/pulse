package app.pulse.feature.connect

import android.app.Activity
import app.pulse.ble.BondBegin
import app.pulse.ble.DeviceBond
import app.pulse.link.LinkAlarm
import app.pulse.link.LinkClock
import app.pulse.link.LinkPorts
import app.pulse.link.StrapLink

internal class Memory : PermissionPromptMemory {
    private val values = linkedSetOf<LinkPermission>()

    override fun prompted(): Set<LinkPermission> = values.toSet()

    override fun markPrompted(permissions: Collection<LinkPermission>) {
        values += permissions
    }
}

internal class MutableProbe(var snapshot: RadioSnapshot) : RadioProbe {
    override fun read(activity: Activity): RadioSnapshot = snapshot
}

/** A link that accepts start and stop and never reports a strap. */
internal fun quietLink(): StrapLink = StrapLink(
    ports = object : LinkPorts {
        override fun startScan() = Unit

        override fun stopScan() = Unit

        override fun bondOf(address: String): DeviceBond = DeviceBond.ABSENT

        override fun beginBond(address: String): BondBegin = BondBegin.NOT_STARTED

        override fun cancelBond() = Unit

        override fun openSession(address: String) = Unit

        override fun closeSession() = Unit
    },
    clock = object : LinkClock {
        override fun after(delayMs: Long, task: () -> Unit): LinkAlarm = LinkAlarm { }
    },
)
