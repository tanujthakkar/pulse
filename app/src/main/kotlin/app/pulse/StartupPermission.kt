package app.pulse

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.pulse.feature.connect.ConnectScreen
import app.pulse.feature.connect.ConnectUi
import app.pulse.feature.connect.LinkPermission
import app.pulse.feature.connect.applicationSettingsIntent
import app.pulse.feature.home.PulsePalette
import app.pulse.link.LinkRuntimePermission

/**
 * Asks for Bluetooth and notifications before the home screen.
 *
 * The connected-device foreground service is not started until scan and
 * connect are granted. Denying them stays on this explanation. Denying
 * notifications does not block the strap; the ongoing notification may
 * simply be hidden.
 */
@Composable
fun StartupPermission(
    onGranted: () -> Unit,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var granted by remember { mutableStateOf(LinkRuntimePermission.bluetoothGranted(context)) }
    var denied by remember { mutableStateOf(false) }
    val permissions = remember { LinkPermission.requiredFor(Build.VERSION.SDK_INT) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (LinkRuntimePermission.bluetoothGranted(context)) {
            denied = false
            granted = true
        } else {
            denied = true
        }
    }
    LaunchedEffect(granted) {
        if (granted) onGranted()
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME &&
                !granted &&
                LinkRuntimePermission.bluetoothGranted(context)
            ) {
                denied = false
                granted = true
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    if (granted) {
        content()
        return
    }
    val request = {
        launcher.launch(permissions.map { it.androidPermission }.toTypedArray())
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(PulsePalette.background),
    ) {
        if (!denied) {
            ConnectScreen(
                ui = ConnectUi.NeedsGrant(permissions),
                onRequestGrant = request,
                onOpenSettings = {},
                onEnableBluetooth = {},
            )
        } else {
            val missing = permissions.filter { permission ->
                activity.checkSelfPermission(permission.androidPermission) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            ConnectScreen(
                ui = ConnectUi.Rationale(
                    missing = missing.ifEmpty { permissions },
                    canAskAgain = missing.any {
                        activity.shouldShowRequestPermissionRationale(it.androidPermission)
                    },
                ),
                onRequestGrant = request,
                onOpenSettings = {
                    activity.startActivity(applicationSettingsIntent(activity.packageName))
                },
                onEnableBluetooth = {},
            )
        }
    }
}

private fun Context.findActivity(): Activity {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    error("Startup permission needs an activity")
}
