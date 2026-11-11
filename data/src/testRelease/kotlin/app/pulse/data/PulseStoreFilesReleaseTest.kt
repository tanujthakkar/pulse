package app.pulse.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PulseStoreFilesReleaseTest {
    @Test
    fun releaseIgnoresTheSimulationFlag() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("pulse.simulation", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("enabled", true)
            .commit()
        assertEquals("pulse.db", PulseStoreFiles.name(context))
    }
}
