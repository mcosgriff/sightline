package io.cosgriff.sightline.ui.screens.devicetools

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.cosgriff.sightline.R
import io.cosgriff.sightline.data.device.ToolsBlock
import io.cosgriff.sightline.data.device.UpdateFailure
import io.cosgriff.sightline.data.device.UpdateStep
import io.cosgriff.sightline.protocol.FirmwareVersions
import io.cosgriff.sightline.ui.components.Choice
import io.cosgriff.sightline.ui.components.Choices
import io.cosgriff.sightline.ui.theme.Numbers
import io.cosgriff.sightline.ui.theme.sightlineColors

@Composable
fun DeviceToolsScreen(deviceId: String, onBack: () -> Unit) {
    val viewModel = hiltViewModel<DeviceToolsViewModel, DeviceToolsViewModel.Factory>(key = "tools-$deviceId") { it.create(deviceId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleStartEffect(viewModel) {
        viewModel.start()
        onStopOrDispose { viewModel.stop() }
    }
    DeviceToolsContent(
        state = state,
        actions = DeviceToolsActions(
            onBack = onBack,
            onPickSource = viewModel::pickSource,
            onScan = viewModel::startScan,
            onStopScan = viewModel::stopScan,
            onCheck = viewModel::checkLatest,
            onInstall = viewModel::install,
            onDismissUpdate = viewModel::dismissUpdate,
            onStopDemoPlayer = viewModel::stopDemoPlayer,
        ),
    )
}

/** What Device tools can ask for. Scan and Install are confirmed in a dialog before they reach these. */
data class DeviceToolsActions(
    val onBack: () -> Unit = {},
    val onPickSource: (String) -> Unit = {},
    val onScan: () -> Unit = {},
    val onStopScan: () -> Unit = {},
    val onCheck: () -> Unit = {},
    val onInstall: () -> Unit = {},
    val onDismissUpdate: () -> Unit = {},
    val onStopDemoPlayer: () -> Unit = {},
)

private enum class Confirm { SCAN, INSTALL }

@Composable
fun DeviceToolsContent(state: DeviceToolsState, actions: DeviceToolsActions, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.sightlineColors
    var confirm by rememberSaveable { mutableStateOf<Confirm?>(null) }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.tools_back))
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.tools_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                state.device?.let { Text(it.friendlyName, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary) }
            }
        }
        when {
            state.missing -> Note(stringResource(R.string.tools_no_device))
            !state.loaded -> Note(stringResource(R.string.tools_reading))
            else -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BlockBanner(state, actions.onStopDemoPlayer)
                ScanCard(state, actions, onScan = { confirm = Confirm.SCAN })
                FirmwareCard(state, actions, onInstall = { confirm = Confirm.INSTALL })
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    val name = state.device?.friendlyName.orEmpty()
    when (confirm) {
        Confirm.SCAN -> ConfirmDialog(
            title = stringResource(R.string.tools_scan_confirm_title),
            body = stringResource(R.string.tools_scan_confirm_body, name),
            confirm = stringResource(R.string.tools_scan_confirm),
            onConfirm = { confirm = null; actions.onScan() },
            onDismiss = { confirm = null },
        )
        Confirm.INSTALL -> state.target?.let { version ->
            ConfirmDialog(
                title = stringResource(R.string.tools_firmware_confirm_title, version),
                body = stringResource(R.string.tools_firmware_confirm_body, name),
                confirm = stringResource(R.string.tools_firmware_confirm),
                onConfirm = { confirm = null; actions.onInstall() },
                onDismiss = { confirm = null },
            )
        }
        null -> Unit
    }
}

@Composable
private fun ConfirmDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.tools_cancel)) } },
    )
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.sightlineColors.textSecondary, modifier = Modifier.padding(20.dp))
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    val colors = MaterialTheme.sightlineColors
    Column(
        Modifier.fillMaxWidth()
            .background(colors.surface, MaterialTheme.shapes.medium)
            .border(1.dp, colors.outline, MaterialTheme.shapes.medium)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun Banner(lines: List<String>, tint: Color, icon: ImageVector, extra: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth()
            .background(tint.copy(alpha = 0.12f), MaterialTheme.shapes.medium)
            .border(1.dp, tint.copy(alpha = 0.5f), MaterialTheme.shapes.medium)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            extra()
        }
    }
}

/** Why scan and update are off right now. A running scan or update shows in its own card instead. */
@Composable
private fun BlockBanner(state: DeviceToolsState, onStopDemoPlayer: () -> Unit) {
    val colors = MaterialTheme.sightlineColors
    when (val block = state.block) {
        is ToolsBlock.InUse -> Banner(
            block.holders.toSortedMap().map { (tuner, holder) ->
                holder?.let { stringResource(R.string.devices_tuner_held_by, tuner, it) }
                    ?: stringResource(R.string.devices_tuner_held_unknown, tuner)
            } + stringResource(R.string.tools_blocked_in_use),
            colors.healthMarginal,
            Icons.Rounded.Warning,
        ) {
            if (state.device?.demo == true) {
                OutlinedButton(onClick = onStopDemoPlayer) { Text(stringResource(R.string.tools_demo_stop_player)) }
            }
        }
        is ToolsBlock.ThisApp -> Banner(
            listOf(stringResource(R.string.tools_blocked_app, block.tuners.joinToString())),
            colors.healthMarginal,
            Icons.Rounded.Warning,
        )
        ToolsBlock.Unreachable -> Banner(listOf(stringResource(R.string.tools_blocked_unreachable)), colors.healthNoLock, Icons.Rounded.ErrorOutline)
        ToolsBlock.Scanning, ToolsBlock.Updating, null -> Unit
    }
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}

@Composable
private fun Body(text: String, color: Color = MaterialTheme.sightlineColors.textSecondary) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

@Composable
private fun sourceLabel(source: String) = when (source) {
    "Antenna" -> stringResource(R.string.tools_scan_source_antenna)
    "Cable" -> stringResource(R.string.tools_scan_source_cable)
    else -> source
}

@Composable
private fun ScanCard(state: DeviceToolsState, actions: DeviceToolsActions, onScan: () -> Unit) {
    val colors = MaterialTheme.sightlineColors
    val lineup = state.lineup
    Card {
        Title(stringResource(R.string.tools_scan_title))
        Body(stringResource(R.string.tools_scan_body))
        Text(pluralStringResource(R.plurals.tools_scan_lineup, state.channelCount, state.channelCount), style = MaterialTheme.typography.bodyMedium)
        if (lineup?.scanning == true) {
            val progress = (lineup.progress ?: 0).coerceIn(0, 100)
            val found = lineup.found ?: 0
            val label = stringResource(R.string.tools_scan_progress_a11y)
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
                color = colors.accent,
                trackColor = colors.outline,
            )
            Text(pluralStringResource(R.plurals.tools_scan_progress, found, progress, found), style = Numbers.info)
            OutlinedButton(onClick = actions.onStopScan) { Text(stringResource(R.string.tools_scan_stop)) }
            return@Card
        }
        if (lineup != null && lineup.sourceList.size > 1) {
            Body(stringResource(R.string.tools_scan_source))
            Choices {
                lineup.sourceList.forEach { source ->
                    Choice(sourceLabel(source), selected = source == state.source, onClick = { actions.onPickSource(source) })
                }
            }
        }
        when {
            state.scanFinished -> Status(stringResource(R.string.tools_scan_finished), colors.healthGood, Icons.Rounded.CheckCircle)
            state.scanRequest == ScanRequest.FAILED -> Status(stringResource(R.string.tools_scan_failed), colors.healthPoor, Icons.Rounded.ErrorOutline)
            lineup == null -> Body(stringResource(R.string.tools_scan_no_answer))
            state.block == null && !lineup.canScan -> Body(stringResource(R.string.tools_scan_not_possible))
        }
        Button(onClick = onScan, enabled = state.canScan) {
            Text(stringResource(if (state.scanRequest == ScanRequest.STARTING) R.string.tools_scan_starting else R.string.tools_scan_start))
        }
    }
}

@Composable
private fun Status(text: String, tint: Color, icon: ImageVector) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp).padding(top = 1.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Fact(label: String, value: String, tint: Color? = null) {
    val colors = MaterialTheme.sightlineColors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, modifier = Modifier.widthIn(min = 112.dp))
        Text(value, style = Numbers.info, color = tint ?: colors.textPrimary, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun FirmwareCard(state: DeviceToolsState, actions: DeviceToolsActions, onInstall: () -> Unit) {
    val colors = MaterialTheme.sightlineColors
    val dash = stringResource(R.string.live_no_value)
    val installed = state.installed
    Card {
        Title(stringResource(R.string.tools_firmware_title))
        Fact(stringResource(R.string.tools_firmware_installed), installed ?: dash)
        val offered = state.offered
        Fact(
            stringResource(R.string.tools_firmware_offered),
            offered ?: stringResource(R.string.tools_firmware_offered_none),
            tint = if (offered != null && installed != null && FirmwareVersions.isNewer(offered, installed)) colors.healthMarginal else null,
        )
        val latest = when (val l = state.latest) {
            LatestCheck.NotChecked -> stringResource(R.string.tools_firmware_not_checked)
            LatestCheck.Checking -> stringResource(R.string.tools_firmware_checking)
            is LatestCheck.Found ->
                if (installed != null && FirmwareVersions.isNewer(l.version, installed)) stringResource(R.string.tools_firmware_newer, l.version)
                else stringResource(R.string.tools_firmware_same, l.version)
            LatestCheck.UnknownFirmware -> stringResource(R.string.tools_firmware_unknown_name)
            LatestCheck.Failed -> stringResource(R.string.tools_firmware_check_failed)
        }
        Fact(stringResource(R.string.tools_firmware_latest), latest)

        val update = state.update
        if (update != null) {
            UpdateProgress(update, actions.onDismissUpdate)
            if (update.running) return@Card
        }
        if (state.latest !is LatestCheck.Found) {
            Body(stringResource(R.string.tools_firmware_check_note))
            OutlinedButton(onClick = actions.onCheck, enabled = state.latest != LatestCheck.Checking) {
                Text(stringResource(R.string.tools_firmware_check))
            }
        }
        state.target?.let { version ->
            Button(onClick = onInstall, enabled = state.canUpdate) { Text(stringResource(R.string.tools_firmware_install, version)) }
        }
    }
}

@Composable
private fun UpdateProgress(step: UpdateStep, onDismiss: () -> Unit) {
    val colors = MaterialTheme.sightlineColors
    val label = stringResource(R.string.tools_update_progress_a11y)
    val bar: @Composable (Float?) -> Unit = { fraction ->
        val m = Modifier.fillMaxWidth().semantics { contentDescription = label }
        if (fraction == null) LinearProgressIndicator(modifier = m, color = colors.accent, trackColor = colors.outline)
        else LinearProgressIndicator(progress = { fraction }, modifier = m, color = colors.accent, trackColor = colors.outline)
    }
    when (step) {
        is UpdateStep.Downloading -> {
            Text(stringResource(R.string.tools_update_downloading, step.version), style = MaterialTheme.typography.bodyMedium)
            bar(step.total?.takeIf { it > 0 }?.let { step.read.toFloat() / it })
        }
        is UpdateStep.Uploading -> {
            Text(stringResource(R.string.tools_update_sending, step.version), style = MaterialTheme.typography.bodyMedium)
            bar(if (step.total > 0) step.sent.toFloat() / step.total else null)
        }
        is UpdateStep.Restarting -> {
            Status(stringResource(R.string.tools_update_restarting, step.version), colors.healthMarginal, Icons.Rounded.Warning)
            bar(null)
        }
        is UpdateStep.Done -> {
            Status(stringResource(R.string.tools_update_done, step.version), colors.healthGood, Icons.Rounded.CheckCircle)
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.tools_update_dismiss)) }
        }
        is UpdateStep.Failed -> {
            val text = when (step.why) {
                UpdateFailure.BLOCKED -> stringResource(R.string.tools_update_failed_blocked)
                UpdateFailure.NO_FIRMWARE_NAME -> stringResource(R.string.tools_update_failed_name)
                UpdateFailure.NOT_FOUND -> stringResource(R.string.tools_update_failed_not_found, step.version)
                UpdateFailure.DOWNLOAD_FAILED -> stringResource(R.string.tools_update_failed_download)
                UpdateFailure.REJECTED -> stringResource(R.string.tools_update_failed_rejected, step.detail.orEmpty())
                UpdateFailure.UPLOAD_FAILED -> stringResource(R.string.tools_update_failed_upload)
                UpdateFailure.NOT_BACK -> stringResource(R.string.tools_update_failed_not_back, step.version)
            }
            Status(text, colors.healthPoor, Icons.Rounded.ErrorOutline)
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.tools_update_dismiss)) }
        }
    }
}
