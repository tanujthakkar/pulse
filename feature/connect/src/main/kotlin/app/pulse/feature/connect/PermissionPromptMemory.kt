package app.pulse.feature.connect

/**
 * Remembers which permissions have already been requested.
 *
 * After a permanent denial the platform reports the same
 * `shouldShowRequestPermissionRationale == false` signal as a fresh install.
 * This memory is how a denial stays on the rationale screen.
 */
interface PermissionPromptMemory {
    fun prompted(): Set<LinkPermission>

    fun markPrompted(permissions: Collection<LinkPermission>)
}
