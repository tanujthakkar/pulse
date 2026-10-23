package app.pulse.feature.debug

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.pulse.ble.BondBegin
import app.pulse.ble.DeviceBond
import app.pulse.data.DeviceDirectory
import app.pulse.data.DeviceRow
import app.pulse.link.LinkAlarm
import app.pulse.link.LinkClock
import app.pulse.link.LinkPorts
import app.pulse.link.StrapLink
import app.pulse.wire.codec.CommandResult
import app.pulse.wire.codec.FrameCodec
import app.pulse.wire.codec.FrameIntake
import app.pulse.wire.codec.FrameRing
import app.pulse.wire.codec.HelloBody
import app.pulse.wire.codec.HelloCommand
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w480dp-h2000dp")
class DebugScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun listIsNewestFirstAndFiltersPauseAndExpand() {
        val ring = FrameRing()
        val intake = FrameIntake(ring)
        val viewModel = DebugViewModel(quietLink(), EmptyDevices(), ring)
        compose.setContent { DebugRoute(viewModel) }
        compose.onNodeWithTag("debug_empty").assertIsDisplayed()
        compose.onNodeWithText("redacted and are not stored", substring = true).assertExists()
        compose.onNodeWithTag("debug_export").assertIsDisplayed()

        compose.runOnIdle {
            intake.offer(false, "fd4b0002", true, HelloCommand.bytes(), 0L)
            intake.offer(true, "fd4b0003", true, helloResponse(), 1L)
        }
        compose.onNodeWithTag("debug_empty").assertDoesNotExist()
        compose.onNodeWithTag("debug_count").assertTextContains("2 stored.", substring = true)
        compose.onNodeWithTag("debug_frame_2").performScrollTo().assertTextContains("in fd4b0003", substring = true)
        compose.onNodeWithTag("debug_frame_2").assertTextContains("opcode 145", substring = true)
        compose.onNodeWithTag("debug_frame_1").performScrollTo().assertTextContains("out fd4b0002", substring = true)
        compose.onAllNodesWithText("type=", substring = true)[0].assertTextContains("type=36", substring = true)

        compose.onNodeWithTag("debug_filter_35").performClick()
        compose.onNodeWithTag("debug_frame_2").assertDoesNotExist()
        compose.onNodeWithTag("debug_frame_1").assertIsDisplayed()
        compose.onNodeWithTag("debug_filter_all").performClick()
        compose.onNodeWithTag("debug_frame_2").assertIsDisplayed()

        compose.onNodeWithTag("debug_frame_2").performClick()
        compose.onNodeWithTag("debug_frame_2").assertTextContains("«redacted»", substring = true)
        compose.onNodeWithText("SECRETBLOCK", substring = true).assertDoesNotExist()

        compose.onNodeWithTag("debug_pause").performClick()
        compose.onNodeWithTag("debug_pause").assertTextContains("Resume")
        compose.onNodeWithTag("debug_count").assertTextContains("Paused.", substring = true)
        compose.runOnIdle {
            intake.offer(true, "2a37", false, byteArrayOf(0x00, 0x48), 2L)
        }
        compose.onNodeWithTag("debug_frame_3").assertDoesNotExist()
        compose.onNodeWithTag("debug_count").assertTextContains("2 stored.", substring = true)

        compose.onNodeWithTag("debug_pause").performClick()
        compose.runOnIdle {
            intake.offer(true, "2a37", false, byteArrayOf(0x00, 0x48), 3L)
        }
        compose.onNodeWithTag("debug_filter_unframed").assertIsDisplayed()
        compose.onNodeWithTag("debug_frame_4").assertTextContains("2a37", substring = true)
        compose.onNodeWithTag("debug_filter_unframed").performClick()
        compose.onNodeWithTag("debug_frame_2").assertDoesNotExist()
        compose.onNodeWithTag("debug_frame_4").assertIsDisplayed()

        val exported = ByteArrayOutputStream()
        compose.runOnIdle { viewModel.export { exported } }
        compose.onNodeWithTag("debug_export_status").assertTextContains("Exported 3 frames.")
        val text = exported.toString(Charsets.UTF_8)
        assertTrue(text.contains("pulse frames 3"))
        assertTrue(text.contains("«redacted»"))
        assertFalse(text.contains("SECRETBLOCK"))
        assertEquals(3, viewModel.stored.value)
    }

    private class EmptyDevices : DeviceDirectory {
        override fun save(row: DeviceRow) = Unit

        override fun latest(): DeviceRow? = null
    }
}

private fun quietLink(): StrapLink = StrapLink(
    ports = object : LinkPorts {
        override fun startScan() = Unit

        override fun stopScan() = Unit

        override fun bondOf(address: String): DeviceBond = DeviceBond.ABSENT

        override fun beginBond(address: String): BondBegin = BondBegin.NOT_STARTED

        override fun cancelBond() = Unit

        override fun openSession(address: String) = Unit

        override fun closeSession() = Unit

        override fun watchHeartRate(address: String) = Unit
    },
    clock = object : LinkClock {
        override fun after(delayMs: Long, task: () -> Unit): LinkAlarm = LinkAlarm { }
    },
)

private fun helloResponse(): ByteArray {
    val body = ByteArray(HelloBody.FINAL_LENGTH)
    body[0] = HelloCommand.REVISION.toByte()
    val word = "SECRETBLOCK".toByteArray(Charsets.US_ASCII)
    for (range in HelloBody.IDENTITY) {
        for (index in range) body[index] = word[(index - range.first) % word.size]
    }
    body[HelloBody.VERSION_OFFSET] = 50
    body[HelloBody.VERSION_OFFSET + 1] = 42
    body[HelloBody.VERSION_OFFSET + 2] = 1
    return FrameCodec.encodeCommandResponse(
        sequence = 2,
        command = HelloCommand.NUMBER,
        originSequence = 1,
        result = CommandResult.SUCCESS,
        body = body,
    )
}
