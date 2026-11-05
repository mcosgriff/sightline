package io.cosgriff.sightline.ui.components

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.cosgriff.sightline.R
import io.cosgriff.sightline.ui.theme.sightlineColors

/** `Manifest.permission.ACCESS_LOCAL_NETWORK` (API 37), spelled out so lint doesn't treat it as an inlined new API. */
private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

/** Whether Sightline may reach the LAN. Android 17 blocks it until the user allows Nearby devices; earlier versions don't ask. */
@Stable
class LocalNetworkAccess internal constructor(granted: Boolean) {
    var granted by mutableStateOf(granted)
        internal set

    /** True once the system won't show its prompt again; the button then opens the app's settings instead. */
    var refused by mutableStateOf(false)
        internal set

    internal var request: () -> Unit = {}
    internal var openSettings: () -> Unit = {}

    fun ask() = if (refused) openSettings() else request()
}

@Composable
fun rememberLocalNetworkAccess(): LocalNetworkAccess {
    val context = LocalContext.current
    val activity = LocalActivity.current
    fun check() = Build.VERSION.SDK_INT < 37 ||
        ContextCompat.checkSelfPermission(context, LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
    val access = remember { LocalNetworkAccess(check()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        access.granted = ok
        // After a refusal the system may still ask once more; after "don't ask again" it shows nothing.
        access.refused = !ok && activity?.shouldShowRequestPermissionRationale(LOCAL_NETWORK) == false
    }
    access.request = { launcher.launch(LOCAL_NETWORK) }
    access.openSettings = {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
    // Granted or revoked in system settings while away: pick it up on return.
    LifecycleResumeEffect(access) {
        access.granted = check()
        onPauseOrDispose { }
    }
    return access
}

/** The one-sentence ask, with the button. Shown where a screen needs the network and doesn't have it. */
@Composable
fun LocalNetworkCard(access: LocalNetworkAccess, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.sightlineColors
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = colors.surface, contentColor = colors.textPrimary),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.network_permission_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(if (access.refused) R.string.network_permission_refused else R.string.network_permission_why),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
            )
            Button(onClick = access::ask) {
                Text(stringResource(if (access.refused) R.string.network_permission_settings else R.string.network_permission_allow))
            }
        }
    }
}
