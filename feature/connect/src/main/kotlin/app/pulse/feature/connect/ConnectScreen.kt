package app.pulse.feature.connect

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
fun ConnectScreen(
    ui: ConnectUi,
    onRequestGrant: () -> Unit,
    onOpenSettings: () -> Unit,
    onEnableBluetooth: () -> Unit,
    modifier: Modifier = Modifier,
    linkPanel: LinkPanel? = null,
    onRetryLink: () -> Unit = {},
    onStopLink: () -> Unit = {},
) {
    when (ui) {
        ConnectUi.Checking -> Column(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp)
                .testTag("connect_checking"),
        ) {
            CircularProgressIndicator()
        }
        is ConnectUi.NeedsGrant -> GateColumn(
            title = stringResource(R.string.connect_permission_title),
            body = permissionBody(ui.permissions),
            tag = "connect_needs_grant",
            modifier = modifier,
        ) {
            Button(
                onClick = onRequestGrant,
                modifier = Modifier
                    .padding(top = 24.dp)
                    .testTag("connect_continue"),
            ) {
                Text(stringResource(R.string.connect_permission_continue))
            }
        }
        is ConnectUi.Rationale -> GateColumn(
            title = stringResource(R.string.connect_rationale_title),
            body = rationaleBody(ui.missing),
            tag = "connect_rationale",
            modifier = modifier,
        ) {
            Button(
                onClick = onOpenSettings,
                modifier = Modifier
                    .padding(top = 24.dp)
                    .testTag("connect_open_settings"),
            ) {
                Text(stringResource(R.string.connect_open_settings))
            }
            if (ui.canAskAgain) {
                Button(
                    onClick = onRequestGrant,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .testTag("connect_try_again"),
                ) {
                    Text(stringResource(R.string.connect_try_again))
                }
            }
        }
        ConnectUi.BluetoothOff -> GateColumn(
            title = stringResource(R.string.connect_bluetooth_off_title),
            body = stringResource(R.string.connect_bluetooth_off_body),
            tag = "connect_bluetooth_off",
            modifier = modifier,
        ) {
            Button(
                onClick = onEnableBluetooth,
                modifier = Modifier
                    .padding(top = 24.dp)
                    .testTag("connect_enable_bluetooth"),
            ) {
                Text(stringResource(R.string.connect_enable_bluetooth))
            }
        }
        ConnectUi.Ready -> {
            val panel = linkPanel
            if (panel == null) {
                GateColumn(
                    title = stringResource(R.string.connect_title),
                    body = stringResource(R.string.connect_body),
                    tag = "connect_ready",
                    modifier = modifier,
                ) {}
            } else {
                LinkPanelContent(
                    panel = panel,
                    onRetry = onRetryLink,
                    onStop = onStopLink,
                    modifier = modifier,
                )
            }
        }
    }
}

@Composable
private fun permissionBody(permissions: List<LinkPermission>): String {
    val notifications = LinkPermission.Notifications in permissions
    val res = if (notifications) {
        R.string.connect_permission_body_notifications
    } else {
        R.string.connect_permission_body
    }
    return stringResource(res)
}

@Composable
private fun rationaleBody(missing: List<LinkPermission>): String {
    val notifications = LinkPermission.Notifications in missing
    val res = if (notifications) {
        R.string.connect_rationale_body_notifications
    } else {
        R.string.connect_rationale_body
    }
    return stringResource(res)
}

@Composable
private fun GateColumn(
    title: String,
    body: String,
    tag: String,
    modifier: Modifier,
    actions: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag(tag),
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineMedium)
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 12.dp),
        )
        actions()
    }
}
