package io.cosgriff.sightline.ui.screens.aim

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

/**
 * One coarse location fix for home (SPEC "Privacy": used once, stored to 2 decimal places), so Aim can turn the
 * compass to true north. Asks for coarse location only, through the platform `LocationManager` (ADR-0002). Returns
 * the action that starts it; [onFix] gets the coordinates, or nothing happens when refused or no fix comes.
 */
@Composable
fun rememberHomeFix(onFix: (latitude: Double, longitude: Double) -> Unit): () -> Unit {
    val context = LocalContext.current
    val callback = rememberUpdatedState(onFix)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) locate(context) { callback.value(it.latitude, it.longitude) }
    }
    return remember(context, launcher) {
        {
            if (hasCoarse(context)) locate(context) { callback.value(it.latitude, it.longitude) }
            else launcher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }
}

private fun hasCoarse(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

private fun locate(context: Context, onFix: (Location) -> Unit) {
    val manager = context.getSystemService(LocationManager::class.java) ?: return
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
    val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
    providers.firstNotNullOfOrNull { manager.getLastKnownLocation(it) }?.let { return onFix(it) }
    val provider = providers.firstOrNull { it != LocationManager.PASSIVE_PROVIDER } ?: return
    LocationManagerCompat.getCurrentLocation(
        manager, provider, null as CancellationSignal?, ContextCompat.getMainExecutor(context),
    ) { location: Location? -> location?.let(onFix) }
}
