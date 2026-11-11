package app.pulse.ble

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SimulatedStrapReleaseTest {
    @Test
    fun releaseIgnoresTheLaunchExtra() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("pulse.simulation", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("enabled", true)
            .commit()
        val intent = Intent().putExtra(SimulatedStrap.EXTRA_ENABLED, true)
        assertFalse(SimulatedStrap.applyIntent(context, intent))
        assertFalse(SimulatedStrap.isEnabled(context))
    }
}
