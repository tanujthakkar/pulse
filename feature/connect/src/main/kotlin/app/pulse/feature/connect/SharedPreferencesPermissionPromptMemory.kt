package app.pulse.feature.connect

import android.content.Context

internal class SharedPreferencesPermissionPromptMemory(
    context: Context,
) : PermissionPromptMemory {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun prompted(): Set<LinkPermission> =
        prefs.getStringSet(KEY, emptySet())
            .orEmpty()
            .mapNotNull { key -> LinkPermission.entries.firstOrNull { it.name == key } }
            .toSet()

    override fun markPrompted(permissions: Collection<LinkPermission>) {
        val merged = prompted().map { it.name }.toMutableSet()
        permissions.forEach { merged += it.name }
        prefs.edit().putStringSet(KEY, merged).commit()
    }

    private companion object {
        const val PREFS = "pulse_connect_permissions"
        const val KEY = "prompted"
    }
}
