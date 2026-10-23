package app.pulse.feature.connect

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectGatePolicyTest {
    @Test
    fun android12Through15NeverRequestsLocation() {
        val location = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        for (sdk in 31..35) {
            val names = LinkPermission.requiredFor(sdk).map { it.androidPermission }
            assertTrue(names.none { it in location || "LOCATION" in it })
            assertEquals(Manifest.permission.BLUETOOTH_SCAN, names[0])
            assertEquals(Manifest.permission.BLUETOOTH_CONNECT, names[1])
            if (sdk >= 33) {
                assertEquals(
                    listOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ),
                    names,
                )
            } else {
                assertEquals(2, names.size)
            }
        }
    }

    @Test
    fun freshInstallOnApi31And32RequestsScanAndConnect() {
        for (sdk in listOf(31, 32)) {
            val ui = ConnectGatePolicy.evaluate(fresh(sdk), prompted = emptySet())
            assertEquals(
                ConnectUi.NeedsGrant(listOf(LinkPermission.Scan, LinkPermission.Connect)),
                ui,
            )
        }
    }

    @Test
    fun freshInstallOnApi33Through35AlsoRequestsNotifications() {
        for (sdk in listOf(33, 34, 35)) {
            val ui = ConnectGatePolicy.evaluate(fresh(sdk), prompted = emptySet())
            assertEquals(
                ConnectUi.NeedsGrant(
                    listOf(
                        LinkPermission.Scan,
                        LinkPermission.Connect,
                        LinkPermission.Notifications,
                    ),
                ),
                ui,
            )
        }
    }

    @Test
    fun denialShowsRationaleEvenWhenThePlatformWillNotAskAgain() {
        val required = LinkPermission.requiredFor(33).toSet()
        val ui = ConnectGatePolicy.evaluate(
            fresh(33),
            prompted = required,
        )
        assertEquals(ConnectUi.Rationale(required.toList(), canAskAgain = false), ui)
    }

    @Test
    fun denialShowsRationaleWithRetryWhenThePlatformWillAskAgain() {
        val required = LinkPermission.requiredFor(34)
        val ui = ConnectGatePolicy.evaluate(
            fresh(34).copy(rationale = required.toSet()),
            prompted = emptySet(),
        )
        assertEquals(ConnectUi.Rationale(required, canAskAgain = true), ui)
    }

    @Test
    fun partialGrantStillBlocksConnect() {
        val ui = ConnectGatePolicy.evaluate(
            fresh(35).copy(granted = setOf(LinkPermission.Scan)),
            prompted = setOf(LinkPermission.Connect),
        )
        assertEquals(
            ConnectUi.Rationale(
                listOf(LinkPermission.Connect, LinkPermission.Notifications),
                canAskAgain = false,
            ),
            ui,
        )
    }

    @Test
    fun missingPermissionWinsOverBluetoothOff() {
        val ui = ConnectGatePolicy.evaluate(
            fresh(31).copy(bluetoothEnabled = false),
            prompted = emptySet(),
        )
        assertTrue(ui is ConnectUi.NeedsGrant)
    }

    @Test
    fun bluetoothOffPromptWhenGrantsAreHeld() {
        val ui = ConnectGatePolicy.evaluate(granted(31, bluetoothEnabled = false), emptySet())
        assertEquals(ConnectUi.BluetoothOff, ui)
    }

    @Test
    fun grantPathIsReadyOnlyWhenRadioIsOn() {
        assertEquals(ConnectUi.Ready, ConnectGatePolicy.evaluate(granted(33, true), emptySet()))
        assertEquals(ConnectUi.Ready, ConnectGatePolicy.evaluate(granted(35, true), emptySet()))
        assertFalse(ConnectGatePolicy.evaluate(granted(32, false), emptySet()) is ConnectUi.Ready)
    }

    private fun fresh(sdk: Int) = RadioSnapshot(
        sdkInt = sdk,
        granted = emptySet(),
        rationale = emptySet(),
        bluetoothEnabled = true,
    )

    private fun granted(sdk: Int, bluetoothEnabled: Boolean) = RadioSnapshot(
        sdkInt = sdk,
        granted = LinkPermission.requiredFor(sdk).toSet(),
        rationale = emptySet(),
        bluetoothEnabled = bluetoothEnabled,
    )
}
