package app.pulse

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.bluetooth.BluetoothAdapter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import androidx.test.platform.app.InstrumentationRegistry
import app.pulse.ble.BondBegin
import app.pulse.ble.DeviceBond
import app.pulse.feature.connect.ConnectBindings
import app.pulse.feature.connect.LinkPermission
import app.pulse.feature.connect.PermissionPromptMemory
import app.pulse.feature.connect.RadioProbe
import app.pulse.feature.connect.RadioSnapshot
import app.pulse.link.LinkAlarm
import app.pulse.link.LinkClock
import app.pulse.link.LinkPorts
import app.pulse.link.StrapLink
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import org.hamcrest.Matchers.allOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import androidx.test.ext.junit.runners.AndroidJUnit4

@HiltAndroidTest
@UninstallModules(ConnectBindings::class, LinkBindings::class)
@RunWith(AndroidJUnit4::class)
class ConnectPathInstrumentedTest {
    private val hiltRule = HiltAndroidRule(this)
    private val scriptRule = ConnectScriptRule()
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: TestRule = RuleChain
        .outerRule(hiltRule)
        .around(scriptRule)
        .around(composeRule)

    @BindValue
    @JvmField
    val radioProbe: RadioProbe = ScriptedRadioProbe()

    @BindValue
    @JvmField
    val permissionPromptMemory: PermissionPromptMemory = EmptyPermissionMemory()

    @BindValue
    @JvmField
    val strapLink: StrapLink = quietStrapLink()

    @Before
    fun inject() {
        hiltRule.inject()
    }

    @Test
    fun grantPathShowsConnectBody() {
        composeRule.onNodeWithText("Looking for a strap.").assertIsDisplayed()
        composeRule.onNodeWithText("Open settings").assertDoesNotExist()
        composeRule.onNodeWithText("Turn on Bluetooth").assertDoesNotExist()
    }

    @Test
    fun denialShowsRationaleAndOpensSettings() {
        composeRule.onNodeWithText("Bluetooth permission is off").assertIsDisplayed()
        composeRule.onNodeWithText("Pairing and connection will live here.").assertDoesNotExist()
        Intents.init()
        try {
            Intents.intending(hasAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS))
                .respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
            composeRule.onNodeWithText("Open settings").performClick()
            composeRule.waitForIdle()
            Intents.intended(
                allOf(
                    hasAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS),
                    hasData(Uri.parse("package:app.pulse")),
                ),
            )
        } finally {
            Intents.release()
        }
    }

    @Test
    fun bluetoothOffShowsEnablePrompt() {
        composeRule.onNodeWithText("Bluetooth is off").assertIsDisplayed()
        composeRule.onNodeWithText("Turn on Bluetooth").assertIsDisplayed()
        composeRule.onNodeWithText("Pairing and connection will live here.").assertDoesNotExist()
        Intents.init()
        try {
            Intents.intending(hasAction(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                .respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
            composeRule.onNodeWithText("Turn on Bluetooth").performClick()
            composeRule.waitForIdle()
            Intents.intended(hasAction(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } finally {
            Intents.release()
        }
    }

    @Test
    fun installedManifestDoesNotRequestLocation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_PERMISSIONS,
            )
        }
        val names = info.requestedPermissions?.toList().orEmpty()
        assertFalse(names.any { "LOCATION" in it })
        assertTrue(names.contains(Manifest.permission.BLUETOOTH_SCAN))
        assertTrue(names.contains(Manifest.permission.BLUETOOTH_CONNECT))
        assertTrue(names.contains(Manifest.permission.POST_NOTIFICATIONS))
        assertEquals("app.pulse", context.packageName)
    }
}

private object ConnectProbeScript {
    var snapshot: RadioSnapshot = readySnapshot()
}

private class ScriptedRadioProbe : RadioProbe {
    override fun read(activity: Activity): RadioSnapshot = ConnectProbeScript.snapshot
}

private class EmptyPermissionMemory : PermissionPromptMemory {
    override fun prompted(): Set<LinkPermission> = emptySet()

    override fun markPrompted(permissions: Collection<LinkPermission>) = Unit
}

private class ConnectScriptRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            ConnectProbeScript.snapshot = when (description.methodName) {
                "denialShowsRationaleAndOpensSettings" -> deniedSnapshot()
                "bluetoothOffShowsEnablePrompt" -> bluetoothOffSnapshot()
                else -> readySnapshot()
            }
            base.evaluate()
        }
    }
}

private fun readySnapshot() = RadioSnapshot(
    sdkInt = 33,
    granted = LinkPermission.requiredFor(33).toSet(),
    rationale = emptySet(),
    bluetoothEnabled = true,
)

private fun deniedSnapshot() = RadioSnapshot(
    sdkInt = 33,
    granted = emptySet(),
    rationale = LinkPermission.requiredFor(33).toSet(),
    bluetoothEnabled = true,
)

private fun bluetoothOffSnapshot() = RadioSnapshot(
    sdkInt = 33,
    granted = LinkPermission.requiredFor(33).toSet(),
    rationale = emptySet(),
    bluetoothEnabled = false,
)

private fun quietStrapLink(): StrapLink = StrapLink(
    ports = object : LinkPorts {
        override fun startScan() = Unit

        override fun stopScan() = Unit

        override fun bondOf(address: String): DeviceBond = DeviceBond.ABSENT

        override fun beginBond(address: String): BondBegin = BondBegin.NOT_STARTED

        override fun cancelBond() = Unit

        override fun openSession(address: String) = Unit

        override fun closeSession() = Unit
    },
    clock = object : LinkClock {
        override fun after(delayMs: Long, task: () -> Unit): LinkAlarm = LinkAlarm { }
    },
)
