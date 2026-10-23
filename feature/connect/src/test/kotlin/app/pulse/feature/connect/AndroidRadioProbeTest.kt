package app.pulse.feature.connect

import android.Manifest
import android.app.Activity
import android.app.Application
import android.bluetooth.BluetoothManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class AndroidRadioProbeTest {
    @Test
    @Config(sdk = [33])
    fun grantedRadioOnIncludesNotificationsAndNotLocation() {
        val snapshot = read(enabled = true, grantAll = true, sdkGrants = 33)
        assertEquals(33, snapshot.sdkInt)
        assertEquals(LinkPermission.requiredFor(33).toSet(), snapshot.granted)
        assertTrue(snapshot.bluetoothEnabled)
        assertTrue(snapshot.granted.none { "LOCATION" in it.androidPermission })
        assertTrue(snapshot.rationale.isEmpty())
    }

    @Test
    @Config(sdk = [31])
    fun api31DoesNotRequireNotifications() {
        val snapshot = read(enabled = true, grantAll = true, sdkGrants = 31)
        assertEquals(31, snapshot.sdkInt)
        assertEquals(
            setOf(LinkPermission.Scan, LinkPermission.Connect),
            snapshot.granted,
        )
        assertFalse(snapshot.granted.contains(LinkPermission.Notifications))
    }

    @Test
    @Config(sdk = [35])
    fun radioOffStaysOffWhenConnectIsGranted() {
        val snapshot = read(enabled = false, grantAll = true, sdkGrants = 35)
        assertFalse(snapshot.bluetoothEnabled)
        assertEquals(LinkPermission.requiredFor(35).toSet(), snapshot.granted)
    }

    @Test
    @Config(sdk = [34])
    fun bluetoothIsNotReadableWithoutConnectPermission() {
        val snapshot = read(enabled = true, grantAll = false, sdkGrants = 34)
        assertTrue(snapshot.granted.isEmpty())
        assertFalse(snapshot.bluetoothEnabled)
    }

    private fun read(enabled: Boolean, grantAll: Boolean, sdkGrants: Int): RadioSnapshot {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val app = ApplicationProvider.getApplicationContext<Application>()
        if (grantAll) {
            val names = LinkPermission.requiredFor(sdkGrants).map { it.androidPermission }
            shadowOf(app).grantPermissions(*names.toTypedArray())
        }
        val adapter = app.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setEnabled(enabled)
        return AndroidRadioProbe(app).read(activity)
    }
}
