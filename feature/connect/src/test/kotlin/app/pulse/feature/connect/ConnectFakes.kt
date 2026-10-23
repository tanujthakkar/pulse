package app.pulse.feature.connect

import android.app.Activity

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
