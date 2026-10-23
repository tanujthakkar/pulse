package app.pulse.feature.connect

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PermissionMemoryTest {
    @Test
    fun remembersADenialAcrossInstances() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val memory = SharedPreferencesPermissionPromptMemory(context)
        assertTrue(memory.prompted().isEmpty())
        memory.markPrompted(listOf(LinkPermission.Scan, LinkPermission.Connect))

        val again = SharedPreferencesPermissionPromptMemory(context)
        assertEquals(
            setOf(LinkPermission.Scan, LinkPermission.Connect),
            again.prompted(),
        )
    }
}
