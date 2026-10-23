package app.pulse.feature.connect

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Connect destination hosted by the app navigation graph.
 *
 * The strap link is composed only when the required grants are held and
 * Bluetooth is on. Callers can replace the platform launches in tests.
 */
@Composable
fun ConnectRoute(
    viewModel: ConnectViewModel = hiltViewModel(),
    requestPermissions: ((Array<String>) -> Unit)? = null,
    openSettings: (() -> Unit)? = null,
    requestEnableBluetooth: (() -> Unit)? = null,
) {
    val activity = LocalContext.current.findActivity()
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val requested = when (val current = viewModel.ui.value) {
            is ConnectUi.NeedsGrant -> current.permissions
            is ConnectUi.Rationale -> current.missing
            else -> LinkPermission.requiredFor(Build.VERSION.SDK_INT)
        }
        viewModel.onPermissionResult(activity, requested, result)
    }
    val enableLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        viewModel.refresh(activity)
    }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refresh(activity)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(activity) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                viewModel.refresh(activity)
            }
        }
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        ContextCompat.registerReceiver(
            activity,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { activity.unregisterReceiver(receiver) }
    }

    LaunchedEffect(viewModel, activity) {
        viewModel.refresh(activity)
    }

    val ui by viewModel.ui.collectAsState()
    val linkPanel by viewModel.linkPanel.collectAsState()
    val launchRequest = requestPermissions ?: { permissionLauncher.launch(it) }
    val launchSettings = openSettings ?: {
        activity.startActivity(applicationSettingsIntent(activity.packageName))
    }
    val launchEnable = requestEnableBluetooth ?: { enableLauncher.launch(enableBluetoothIntent()) }

    LaunchedEffect(ui) {
        val pending = ui as? ConnectUi.NeedsGrant ?: return@LaunchedEffect
        launchRequest(pending.permissions.map { it.androidPermission }.toTypedArray())
    }

    ConnectScreen(
        ui = ui,
        onRequestGrant = {
            val permissions = when (val current = ui) {
                is ConnectUi.NeedsGrant -> current.permissions
                is ConnectUi.Rationale -> current.missing
                else -> return@ConnectScreen
            }
            launchRequest(permissions.map { it.androidPermission }.toTypedArray())
        },
        onOpenSettings = launchSettings,
        onEnableBluetooth = launchEnable,
        linkPanel = linkPanel,
        onRetryLink = viewModel::retryLink,
        onStopLink = viewModel::stopLink,
    )
}

private fun Context.findActivity(): Activity {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    error("ConnectRoute must be hosted by an Activity")
}
