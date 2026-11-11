package app.pulse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.pulse.feature.home.PulsePalette
import app.pulse.feature.sync.SyncViewModel
import app.pulse.metrics.BodyProfile
import app.pulse.metrics.Sex

/** Connect, Debug, and Edge import stay reachable from here. */
@Composable
fun MoreRoute(
    onConnect: () -> Unit,
    onDebug: () -> Unit,
    onImport: () -> Unit,
    onSimulationChanged: () -> Unit = {},
    viewModel: SyncViewModel = hiltViewModel(),
    profileModel: ProfileViewModel = hiltViewModel(),
) {
    val ackEnabled by viewModel.historyAckEnabled.collectAsState(viewModel.currentHistoryAck())
    val profile by profileModel.profile.collectAsState()
    val profileStatus by profileModel.status.collectAsState()
    MoreScreen(
        onConnect = onConnect,
        onDebug = onDebug,
        onImport = onImport,
        ackEnabled = ackEnabled,
        onAckEnabled = viewModel::setHistoryAckEnabled,
        onSimulationChanged = onSimulationChanged,
        profile = profile,
        profileStatus = profileStatus,
        onSaveProfile = profileModel::save,
    )
}

@Composable
fun MoreScreen(
    onConnect: () -> Unit,
    onDebug: () -> Unit,
    onImport: () -> Unit,
    ackEnabled: Boolean,
    onAckEnabled: (Boolean) -> Unit,
    onSimulationChanged: () -> Unit = {},
    profile: BodyProfile? = null,
    profileStatus: String = "",
    onSaveProfile: (Sex, String, String, String) -> Unit = { _, _, _, _ -> },
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize().background(PulsePalette.background)) {
    val scroll = if (constraints.hasBoundedHeight) Modifier.verticalScroll(rememberScrollState()) else Modifier
    Column(
        Modifier
            .fillMaxWidth()
            .then(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "More",
            color = PulsePalette.text,
            style = MaterialTheme.typography.headlineMedium,
        )
        ProfileForm(profile, profileStatus, onSaveProfile)
        Button(onClick = onConnect, modifier = Modifier.testTag("more_connect")) { Text("Connect") }
        Button(onClick = onDebug, modifier = Modifier.testTag("more_debug")) { Text("Debug") }
        Button(onClick = onImport, modifier = Modifier.testTag("more_import")) { Text("Import") }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Acknowledge saved history",
                color = PulsePalette.text,
                style = MaterialTheme.typography.bodyLarge,
            )
            Switch(
                checked = ackEnabled,
                onCheckedChange = onAckEnabled,
                modifier = Modifier.testTag("sync_ack_switch"),
            )
        }
        Text(
            "Command 23 is sent only after a chunk is saved, and only while this is on.",
            color = PulsePalette.muted,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("more_ack_note"),
        )
        SimulatedStrapControls(onRestart = onSimulationChanged)
    }
    }
}

@Composable
private fun ProfileForm(
    profile: BodyProfile?,
    status: String,
    onSave: (Sex, String, String, String) -> Unit,
) {
    var sex by remember(profile) { mutableStateOf(profile?.sex ?: Sex.FEMALE) }
    var age by remember(profile) { mutableStateOf(profile?.ageYears?.toString().orEmpty()) }
    var height by remember(profile) { mutableStateOf(profile?.heightCm?.toString().orEmpty()) }
    var weight by remember(profile) { mutableStateOf(if (profile == null) "" else profile.weightKg.toString()) }
    Text("Body profile", color = PulsePalette.text, style = MaterialTheme.typography.titleMedium)
    Text(
        "VO2 max and calories stay blank until this is saved.",
        color = PulsePalette.muted,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag("profile_prompt"),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { sex = Sex.FEMALE }, modifier = Modifier.testTag("profile_female")) {
            Text(if (sex == Sex.FEMALE) "Female · selected" else "Female", color = PulsePalette.text)
        }
        TextButton(onClick = { sex = Sex.MALE }, modifier = Modifier.testTag("profile_male")) {
            Text(if (sex == Sex.MALE) "Male · selected" else "Male", color = PulsePalette.text)
        }
    }
    OutlinedTextField(age, { age = it }, label = { Text("Age") }, modifier = Modifier.fillMaxWidth().testTag("profile_age"))
    OutlinedTextField(height, { height = it }, label = { Text("Height (cm)") }, modifier = Modifier.fillMaxWidth().testTag("profile_height"))
    OutlinedTextField(weight, { weight = it }, label = { Text("Weight (kg)") }, modifier = Modifier.fillMaxWidth().testTag("profile_weight"))
    Button(onClick = { onSave(sex, age, height, weight) }, modifier = Modifier.testTag("profile_save")) {
        Text("Save profile")
    }
    if (status.isNotEmpty()) {
        Text(status, color = PulsePalette.text, modifier = Modifier.testTag("profile_status"))
    }
}
