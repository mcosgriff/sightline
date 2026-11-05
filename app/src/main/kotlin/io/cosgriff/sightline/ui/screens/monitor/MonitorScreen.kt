package io.cosgriff.sightline.ui.screens.monitor

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.cosgriff.sightline.R
import io.cosgriff.sightline.ui.components.LocalNetworkCard
import io.cosgriff.sightline.ui.components.rememberLocalNetworkAccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * Monitor: the setup form, then the running session. Starting asks, once each, to post notifications (the ongoing
 * one and the dip ones) and for the battery optimisation exemption (SPEC "Monitor mode › Service"); the session
 * starts whatever the answers.
 */
@Composable
fun MonitorScreen(viewModel: MonitorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val network = rememberLocalNetworkAccess()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var askBattery by remember { mutableStateOf(false) }
    val now by produceState(viewModel.clock.millis(), viewModel) {
        while (true) {
            value = viewModel.clock.millis()
            delay(1_000)
        }
    }

    val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { viewModel.start() }
    fun askBatteryOrStart() = scope.launch {
        val power = context.getSystemService(PowerManager::class.java)
        if (!power.isIgnoringBatteryOptimizations(context.packageName) && !viewModel.batteryExemptionAsked()) askBattery = true
        else viewModel.start()
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { askBatteryOrStart() }
    val onStart: () -> Unit = {
        scope.launch {
            val needsNotifications = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                !viewModel.notificationsAsked()
            if (needsNotifications) {
                viewModel.markNotificationsAsked()
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                askBatteryOrStart()
            }
        }
    }

    if (askBattery) {
        BatteryDialog(
            onAllow = {
                askBattery = false
                viewModel.markBatteryExemptionAsked()
                try {
                    batteryLauncher.launch(batteryExemptionIntent(context.packageName))
                } catch (_: ActivityNotFoundException) {
                    viewModel.start() // a phone without the screen: start anyway
                }
            },
            onLater = {
                askBattery = false
                viewModel.markBatteryExemptionAsked()
                viewModel.start()
            },
        )
    }

    MonitorContent(
        state = state,
        nowMs = now,
        zone = ZoneId.systemDefault(),
        networkCard = if (network.granted) null else ({ LocalNetworkCard(network) }),
        actions = MonitorActions(
            onSelectDevice = viewModel::selectDevice,
            onToggleChannel = viewModel::toggleChannel,
            onRotate = viewModel::setRotate,
            onTuner = viewModel::setTuner,
            onDwell = viewModel::setDwell,
            onDuration = viewModel::setDuration,
            onPoll = viewModel::setPoll,
            onDipSnq = viewModel::setDipSnq,
            onFetchWeather = viewModel::setFetchWeather,
            onNotifyDips = viewModel::setNotifyDips,
            onStart = onStart,
            onStop = viewModel::stop,
            onDismiss = viewModel::dismiss,
        ),
    )
}

// The direct exemption request is restricted on Play; Sightline is sideloaded, and SPEC "Monitor mode › Service"
// asks for exactly this, once, with a sentence saying why.
@SuppressLint("BatteryLife")
private fun batteryExemptionIntent(packageName: String) =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$packageName".toUri())

@Composable
private fun BatteryDialog(onAllow: () -> Unit, onLater: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(stringResource(R.string.monitor_battery_title)) },
        text = { Text(stringResource(R.string.monitor_battery_why)) },
        confirmButton = { TextButton(onClick = onAllow) { Text(stringResource(R.string.monitor_battery_allow)) } },
        dismissButton = { TextButton(onClick = onLater) { Text(stringResource(R.string.monitor_battery_later)) } },
    )
}
