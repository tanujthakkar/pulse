package app.pulse.feature.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pulse.link.LinkBlock
import app.pulse.link.LinkPhase
import app.pulse.link.LinkSnapshot
import app.pulse.link.StrapLinkLimits
import app.pulse.link.commandBatteryLine
import app.pulse.link.firmwareNote
import app.pulse.link.handshakeLine
import app.pulse.link.helloFirmwareLine
import app.pulse.link.informationFirmwareLine
import app.pulse.link.serviceBatteryLine

/**
 * Copy for one [LinkSnapshot]. [pairingHelp] is the screen shown when the
 * strap is still bonded to another phone.
 */
data class LinkPanel(
    val title: String,
    val status: String,
    val address: String?,
    val pairingHelp: Boolean,
    val helpTitle: String,
    val helpBody: String,
    val allowRetry: Boolean,
    val showStop: Boolean,
    val handshake: String?,
    val helloFirmware: String?,
    val informationFirmware: String?,
    val firmwareNote: String?,
    val commandBattery: String?,
    val serviceBattery: String?,
    val offerRepair: Boolean,
) {
    companion object {
        const val HELP_TITLE: String = "Release the other bond"

        val HELP_BODY: String = """
            The strap keeps one encrypted bond at a time. While another phone holds that bond, this phone cannot open the strap's own channel.

            Quit the other app, or turn Bluetooth off on that phone, so it releases the bond. Then tap the strap firmly and repeatedly until the LEDs flash blue. Pairing mode works after the other phone has let go. If this phone still lists the strap under system Bluetooth, forget that device there.

            Tap Try again. Pulse bonds at most twice, then stops.

            Bonding here can move the strap off the official app. That app may need to pair again afterwards.
        """.trimIndent()

        fun from(snapshot: LinkSnapshot): LinkPanel {
            val pairingHelp = snapshot.phase == LinkPhase.PAIRING_HELP
            return LinkPanel(
                title = "Connect",
                status = statusFor(snapshot),
                address = snapshot.address,
                pairingHelp = pairingHelp,
                helpTitle = HELP_TITLE,
                helpBody = HELP_BODY,
                allowRetry = snapshot.phase == LinkPhase.FAILED,
                showStop = snapshot.phase == LinkPhase.SCANNING ||
                    snapshot.phase == LinkPhase.BONDING ||
                    snapshot.phase == LinkPhase.CONNECTING ||
                    snapshot.phase == LinkPhase.LINKED,
                handshake = handshakeLine(snapshot.hello, snapshot.heartRateLive),
                helloFirmware = helloFirmwareLine(snapshot.helloFirmware),
                informationFirmware = informationFirmwareLine(snapshot.informationFirmware),
                firmwareNote = firmwareNote(snapshot.helloFirmware, snapshot.informationFirmware),
                commandBattery = commandBatteryLine(snapshot.commandBatteryPercent),
                serviceBattery = serviceBatteryLine(snapshot.serviceBatteryPercent),
                offerRepair = snapshot.offerRepair,
            )
        }

        private fun statusFor(snapshot: LinkSnapshot): String = when (snapshot.phase) {
            LinkPhase.IDLE -> "Ready to look for a strap."
            LinkPhase.SCANNING -> "Looking for a strap."
            LinkPhase.BONDING ->
                "Bonding, attempt ${snapshot.bondAttempt} of ${StrapLinkLimits.BOND_ATTEMPTS}."
            LinkPhase.CONNECTING ->
                "Connecting, attempt ${snapshot.connectAttempt} of ${StrapLinkLimits.CONNECT_ATTEMPTS}."
            LinkPhase.LINKED -> "Bonded and connected."
            LinkPhase.PAIRING_HELP -> "The strap is still bonded to another phone."
            LinkPhase.FAILED -> failed(snapshot)
        }

        private fun failed(snapshot: LinkSnapshot): String = when (snapshot.block) {
            LinkBlock.SCAN -> if (snapshot.bluetoothOff) {
                "Bluetooth is off."
            } else {
                "The scan stopped before a strap was found."
            }
            LinkBlock.BOND -> "Bonding stopped after ${StrapLinkLimits.BOND_ATTEMPTS} attempts."
            LinkBlock.CONNECT -> "The connection stopped after ${StrapLinkLimits.CONNECT_ATTEMPTS} attempts."
            LinkBlock.NONE -> "The link stopped."
        }
    }
}

@Composable
fun LinkPanelContent(
    panel: LinkPanel,
    onRetry: () -> Unit,
    onStop: () -> Unit,
    onRepair: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .testTag("connect_ready"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = panel.title, style = MaterialTheme.typography.headlineMedium)
        Text(text = panel.status, style = MaterialTheme.typography.bodyLarge)
        if (panel.address != null) {
            Text(text = panel.address, style = MaterialTheme.typography.bodyMedium)
        }
        panel.handshake?.let { line ->
            Text(text = line, style = MaterialTheme.typography.bodyLarge)
        }
        panel.helloFirmware?.let { line ->
            Text(text = line, style = MaterialTheme.typography.bodyLarge)
        }
        panel.informationFirmware?.let { line ->
            Text(text = line, style = MaterialTheme.typography.bodyLarge)
        }
        panel.firmwareNote?.let { line ->
            Text(text = line, style = MaterialTheme.typography.bodyLarge)
        }
        panel.commandBattery?.let { line ->
            Text(text = line, style = MaterialTheme.typography.bodyLarge)
        }
        panel.serviceBattery?.let { line ->
            Text(text = line, style = MaterialTheme.typography.bodyLarge)
        }
        if (panel.pairingHelp) {
            Text(text = panel.helpTitle, style = MaterialTheme.typography.titleLarge)
            Text(text = panel.helpBody, style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onRetry) {
                Text(text = stringResource(R.string.connect_try_again))
            }
        } else if (panel.allowRetry) {
            Button(onClick = onRetry) {
                Text(text = stringResource(R.string.connect_try_again))
            }
        }
        if (panel.offerRepair) {
            Button(onClick = onRepair) {
                Text(text = "Re-pair")
            }
        }
        if (panel.showStop) {
            Button(onClick = onStop) {
                Text(text = "Stop")
            }
        }
    }
}
