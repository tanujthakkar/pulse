package app.pulse.feature.connect

import android.Manifest
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.pulse.link.HelloProgress
import app.pulse.link.LinkPhase
import app.pulse.link.LinkSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConnectRouteTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun grantPathShowsConnectBody() {
        val vm = ConnectViewModel(Memory(), MutableProbe(granted(33, bluetoothEnabled = true)), quietLink())
        compose.setContent {
            MaterialTheme {
                ConnectRoute(
                    viewModel = vm,
                    requestPermissions = { error("grant path must not request permissions") },
                    openSettings = { error("grant path must not open settings") },
                    requestEnableBluetooth = { error("grant path must not prompt for Bluetooth") },
                )
            }
        }
        compose.onNodeWithText("Looking for a strap.").assertIsDisplayed()
        compose.onNodeWithText("Open settings").assertDoesNotExist()
        compose.onNodeWithText("Turn on Bluetooth").assertDoesNotExist()
    }

    @Test
    fun freshInstallRequestsScanConnectAndNotifications() {
        val requested = mutableListOf<List<String>>()
        val vm = ConnectViewModel(Memory(), MutableProbe(fresh(33)), quietLink())
        compose.setContent {
            MaterialTheme {
                ConnectRoute(
                    viewModel = vm,
                    requestPermissions = { requested += it.toList() },
                    openSettings = {},
                    requestEnableBluetooth = {},
                )
            }
        }
        compose.onNodeWithText("Allow Bluetooth").assertIsDisplayed()
        compose.onNodeWithText("Pairing and connection will live here.").assertDoesNotExist()
        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.POST_NOTIFICATIONS,
            ),
            requested.last(),
        )
        assertTrue(requested.last().none { "LOCATION" in it })
    }

    @Test
    fun freshInstallOnAndroid12DoesNotRequestNotifications() {
        val requested = mutableListOf<List<String>>()
        val vm = ConnectViewModel(Memory(), MutableProbe(fresh(31)), quietLink())
        compose.setContent {
            MaterialTheme {
                ConnectRoute(
                    viewModel = vm,
                    requestPermissions = { requested += it.toList() },
                    openSettings = {},
                    requestEnableBluetooth = {},
                )
            }
        }
        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            ),
            requested.last(),
        )
    }

    @Test
    fun denialShowsRationaleAndSettingsDeepLink() {
        var openedSettings = false
        val memory = Memory().apply { markPrompted(LinkPermission.requiredFor(33)) }
        val vm = ConnectViewModel(
            memory,
            MutableProbe(fresh(33)),
            quietLink(),
        )
        compose.setContent {
            MaterialTheme {
                ConnectRoute(
                    viewModel = vm,
                    requestPermissions = { error("denial must not re-request before settings") },
                    openSettings = { openedSettings = true },
                    requestEnableBluetooth = {},
                )
            }
        }
        compose.onNodeWithText("Bluetooth permission is off").assertIsDisplayed()
        compose.onNodeWithText("Open settings").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertDoesNotExist()
        compose.onNodeWithText("Pairing and connection will live here.").assertDoesNotExist()
        compose.onNodeWithText("Open settings").performClick()
        assertTrue(openedSettings)
    }

    @Test
    fun denialThatCanBeAskedAgainStillOffersSettings() {
        var openedSettings = false
        val requested = mutableListOf<List<String>>()
        val vm = ConnectViewModel(
            Memory(),
            MutableProbe(fresh(33).copy(rationale = LinkPermission.requiredFor(33).toSet())),
            quietLink(),
        )
        compose.setContent {
            MaterialTheme {
                ConnectRoute(
                    viewModel = vm,
                    requestPermissions = { requested += it.toList() },
                    openSettings = { openedSettings = true },
                    requestEnableBluetooth = {},
                )
            }
        }
        compose.onNodeWithText("Try again").assertIsDisplayed()
        compose.onNodeWithText("Open settings").performClick()
        assertTrue(openedSettings)
        compose.onNodeWithText("Try again").performClick()
        assertEquals(LinkPermission.requiredFor(33).map { it.androidPermission }, requested.last())
        assertTrue(requested.last().none { "LOCATION" in it })
    }

    @Test
    fun bluetoothOffShowsEnablePrompt() {
        var prompted = false
        val vm = ConnectViewModel(Memory(), MutableProbe(granted(34, bluetoothEnabled = false)), quietLink())
        compose.setContent {
            MaterialTheme {
                ConnectRoute(
                    viewModel = vm,
                    requestPermissions = { error("bluetooth-off must not request permissions") },
                    openSettings = { error("bluetooth-off must not open settings") },
                    requestEnableBluetooth = { prompted = true },
                )
            }
        }
        compose.onNodeWithText("Bluetooth is off").assertIsDisplayed()
        compose.onNodeWithText("Turn on Bluetooth").assertIsDisplayed()
        compose.onNodeWithText("Pairing and connection will live here.").assertDoesNotExist()
        compose.onNodeWithText("Turn on Bluetooth").performClick()
        assertTrue(prompted)
    }

    @Test
    fun linkedPanelShowsFirmwareBatterySourcesAndRepair() {
        val panel = LinkPanel.from(
            LinkSnapshot(
                phase = LinkPhase.LINKED,
                hello = HelloProgress.FAILED,
                offerRepair = true,
                heartRateLive = true,
                helloFirmware = "50.42.1.0",
                informationFirmware = "50.42.1.1",
                commandBatteryPercent = 80,
                serviceBatteryPercent = 81,
            ),
        )
        var repaired = false
        compose.setContent {
            MaterialTheme {
                LinkPanelContent(
                    panel = panel,
                    onRetry = {},
                    onStop = {},
                    onRepair = { repaired = true },
                )
            }
        }
        compose.onNodeWithText("Bonded and connected.").assertIsDisplayed()
        compose.onNodeWithText("Hello failed. Live heart rate is still on.").assertIsDisplayed()
        compose.onNodeWithText("Firmware (hello): 50.42.1.0").assertIsDisplayed()
        compose.onNodeWithText("Firmware (device information): 50.42.1.1").assertIsDisplayed()
        compose.onNodeWithText("Firmware strings differ.").assertIsDisplayed()
        compose.onNodeWithText("Battery (command 26): 80%").assertIsDisplayed()
        compose.onNodeWithText("Battery (standard battery service): 81%").assertIsDisplayed()
        compose.onNodeWithText("Re-pair").performClick()
        assertTrue(repaired)
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
