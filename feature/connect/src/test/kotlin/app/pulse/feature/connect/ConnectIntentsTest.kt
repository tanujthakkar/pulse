package app.pulse.feature.connect

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.net.Uri
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConnectIntentsTest {
    @Test
    fun settingsDeepLinkOpensThisAppsDetails() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val intent = applicationSettingsIntent(activity.packageName)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals(Uri.fromParts("package", activity.packageName, null), intent.data)
    }

    @Test
    fun enablePromptIsTheSystemBluetoothRequest() {
        val intent = enableBluetoothIntent()
        assertEquals(BluetoothAdapter.ACTION_REQUEST_ENABLE, intent.action)
        assertNull(intent.data)
    }
}
