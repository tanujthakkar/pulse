package app.pulse

import java.io.File
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManifestPrivacyTest {
    @Test
    fun manifestsDoNotRequestLocation() {
        val root = repoRoot()
        val manifests = root.walkTopDown()
            .onEnter { dir -> dir.name != "build" && dir.name != ".gradle" && dir.name != ".git" }
            .filter { it.isFile && it.name == "AndroidManifest.xml" }
            .toList()
        assertTrue(manifests.isNotEmpty())
        manifests.forEach { manifest ->
            val text = manifest.readText()
            assertFalse(
                text.contains("android.permission.ACCESS_FINE_LOCATION"),
                manifest.path,
            )
            assertFalse(
                text.contains("android.permission.ACCESS_COARSE_LOCATION"),
                manifest.path,
            )
        }
        val app = File(root, "app/src/main/AndroidManifest.xml").readText()
        assertTrue(app.contains("android.permission.BLUETOOTH_SCAN"))
        assertTrue(app.contains("android.permission.BLUETOOTH_CONNECT"))
        assertTrue(app.contains("android.permission.POST_NOTIFICATIONS"))
        assertTrue(app.contains("android.permission.FOREGROUND_SERVICE"))
        assertTrue(app.contains("android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE"))
        assertTrue(app.contains("neverForLocation"))
        assertFalse(app.contains("android.permission.INTERNET"))
        val link = File(root, "link/src/main/AndroidManifest.xml").readText()
        assertTrue(link.contains(".StrapLinkService"))
        assertTrue(link.contains("android:foregroundServiceType=\"connectedDevice\""))
        assertTrue(link.contains("android:exported=\"false\""))
        assertTrue(link.contains("android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE"))
        assertTrue(link.contains("android.permission.POST_NOTIFICATIONS"))
    }

    private fun repoRoot(): File {
        var dir = File("").absoluteFile
        repeat(6) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile ?: return dir
        }
        error("repo root not found from ${File("").absolutePath}")
    }
}
