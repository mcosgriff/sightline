package io.cosgriff.sightline.ui.screens.diagnostics

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.cosgriff.sightline.BuildConfig
import io.cosgriff.sightline.R
import io.cosgriff.sightline.data.device.Diagnostics
import io.cosgriff.sightline.data.device.TunerHolder
import io.cosgriff.sightline.protocol.ControlResult
import io.cosgriff.sightline.protocol.Probe
import io.cosgriff.sightline.protocol.TunerDebug
import io.cosgriff.sightline.protocol.TunerSnapshot
import io.cosgriff.sightline.ui.theme.Numbers
import io.cosgriff.sightline.ui.theme.sightlineColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun DiagnosticsScreen(deviceId: String, onBack: () -> Unit) {
    val viewModel = hiltViewModel<DiagnosticsViewModel, DiagnosticsViewModel.Factory>(key = "diagnostics-$deviceId") { it.create(deviceId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleStartEffect(viewModel) {
        viewModel.start()
        onStopOrDispose { viewModel.stop() }
    }
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    DiagnosticsContent(
        state = state,
        zone = zone,
        onBack = onBack,
        onRelease = viewModel::release,
        onShare = { d -> shareReport(context, diagnosticsReport(context, d, BuildConfig.VERSION_NAME, zone)) },
    )
}

@Composable
fun DiagnosticsContent(
    state: DiagnosticsState,
    zone: ZoneId,
    onBack: () -> Unit,
    onRelease: (Int) -> Unit,
    onShare: (Diagnostics) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.sightlineColors
    val read = state as? DiagnosticsState.Read
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.diagnostics_back))
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.diagnostics_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                read?.let { Text(it.diagnostics.device.friendlyName, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary) }
            }
            if (read != null) {
                IconButton(onClick = { onShare(read.diagnostics) }) {
                    Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.diagnostics_share))
                }
            }
        }
        when (state) {
            DiagnosticsState.Loading -> Note(stringResource(R.string.diagnostics_reading))
            DiagnosticsState.NoDevice -> Note(stringResource(R.string.diagnostics_no_device))
            is DiagnosticsState.Read -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val d = state.diagnostics
                if (d.snapshot.unreachable) Banner(stringResource(R.string.diagnostics_unreachable), colors.healthNoLock, Icons.Rounded.ErrorOutline)
                DeviceCard(d)
                d.snapshot.tuners.forEach { t ->
                    TunerCard(
                        tuner = t,
                        holder = d.holders[t.tuner],
                        releasing = state.releasing == t.tuner,
                        releaseFailed = state.releaseFailed == t.tuner,
                        onRelease = { onRelease(t.tuner) },
                    )
                }
                Text(
                    stringResource(R.string.diagnostics_footer, time(d.readAtMs, zone)),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.sightlineColors.textSecondary, modifier = Modifier.padding(20.dp))
}

@Composable
private fun Banner(text: String, tint: Color, icon: ImageVector) {
    Row(
        Modifier.fillMaxWidth()
            .background(tint.copy(alpha = 0.12f), MaterialTheme.shapes.medium)
            .border(1.dp, tint.copy(alpha = 0.5f), MaterialTheme.shapes.medium)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    val colors = MaterialTheme.sightlineColors
    Column(
        Modifier.fillMaxWidth()
            .background(colors.surface, MaterialTheme.shapes.medium)
            .border(1.dp, colors.outline, MaterialTheme.shapes.medium)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun DeviceCard(d: Diagnostics) {
    val colors = MaterialTheme.sightlineColors
    val dash = stringResource(R.string.live_no_value)
    Card {
        Text(stringResource(R.string.diagnostics_device), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Fact(stringResource(R.string.diagnostics_model), d.snapshot.system.valueOf("/sys/hwmodel") ?: d.device.model.ifEmpty { dash })
        Fact(stringResource(R.string.diagnostics_firmware), d.snapshot.system.valueOf("/sys/version") ?: d.device.firmware.ifEmpty { dash })
        Fact(stringResource(R.string.diagnostics_firmware_name), d.snapshot.system.valueOf("/sys/model") ?: d.info?.firmwareName ?: dash)
        val upgrade = d.info?.upgradeAvailable
        val upgradeText = when {
            upgrade != null -> stringResource(R.string.diagnostics_update_available, upgrade)
            d.info != null -> stringResource(R.string.diagnostics_update_none)
            else -> stringResource(R.string.diagnostics_update_unknown)
        }
        Fact(stringResource(R.string.diagnostics_update), upgradeText, mono = false, tint = if (upgrade != null) colors.healthMarginal else null)
        Fact(stringResource(R.string.diagnostics_address), d.device.address)
        Fact(stringResource(R.string.diagnostics_device_id), d.device.deviceId)
        Fact(stringResource(R.string.diagnostics_phone_address), d.snapshot.localAddress ?: dash)
        d.snapshot.system.valueOf("/sys/features")?.let { features ->
            Text(stringResource(R.string.diagnostics_features), style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
            Text(features.trimEnd(), style = Numbers.info.copy(fontSize = Numbers.info.fontSize * 0.85f), color = colors.textSecondary)
        }
    }
}

@Composable
private fun Fact(label: String, value: String, mono: Boolean = true, tint: Color? = null) {
    val colors = MaterialTheme.sightlineColors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, modifier = Modifier.widthIn(min = 112.dp))
        Text(
            value,
            style = if (mono) Numbers.info else MaterialTheme.typography.bodyMedium,
            color = tint ?: colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TunerCard(tuner: TunerSnapshot, holder: TunerHolder, releasing: Boolean, releaseFailed: Boolean, onRelease: () -> Unit) {
    val colors = MaterialTheme.sightlineColors
    var raw by rememberSaveable(tuner.tuner) { mutableStateOf(false) }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.devices_tuner, tuner.tuner),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            HolderChip(holder)
        }
        holderNote(holder)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary) }
        if (holder is TunerHolder.ThisPhone && holder.staleKey) {
            OutlinedButton(onClick = onRelease, enabled = !releasing) {
                Text(stringResource(if (releasing) R.string.diagnostics_releasing else R.string.diagnostics_release))
            }
        }
        if (releaseFailed) {
            Text(stringResource(R.string.diagnostics_release_failed), style = MaterialTheme.typography.bodySmall, color = colors.healthPoor)
        }
        tuner.value("status")?.let { Text(it.trim(), style = Numbers.info.copy(fontSize = Numbers.info.fontSize * 0.85f)) }
        tuner.debug?.let { Counters(it) }
        tuner.target?.let { Fact(stringResource(R.string.diagnostics_target), it) }
        tuner.value("vchannel")?.takeIf { it != "none" }?.let { Fact(stringResource(R.string.diagnostics_vchannel), it) }
        tuner.value("streaminfo")?.trim()?.takeIf { it.isNotEmpty() && it != "none" }?.let {
            Fact(stringResource(R.string.diagnostics_programs), it)
        }
        Text(
            stringResource(if (raw) R.string.diagnostics_raw_hide else R.string.diagnostics_raw_show),
            style = MaterialTheme.typography.labelLarge,
            color = colors.accent,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button) { raw = !raw }
                .padding(vertical = 14.dp),
        )
        if (raw) RawReplies(tuner.probes)
    }
}

@Composable
private fun Counters(debug: TunerDebug) {
    val dash = stringResource(R.string.live_no_value)
    fun n(v: Long?) = v?.toString() ?: dash
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Counter(stringResource(R.string.diagnostics_transport_errors), n(debug.transportErrors), bad = (debug.transportErrors ?: 0) > 0)
        Counter(stringResource(R.string.diagnostics_crc_errors), n(debug.crcErrors), bad = (debug.crcErrors ?: 0) > 0)
        Counter(stringResource(R.string.diagnostics_resyncs), n(debug.resync), bad = (debug.resync ?: 0) > 0)
        Counter(stringResource(R.string.diagnostics_overflows), n(debug.overflow), bad = (debug.overflow ?: 0) > 0)
        Counter(stringResource(R.string.diagnostics_network_errors), n(debug.networkErrors), bad = (debug.networkErrors ?: 0) > 0)
        Counter(stringResource(R.string.diagnostics_network_stops), n(debug.networkStops), bad = (debug.networkStops ?: 0) > 0)
        Counter(
            stringResource(R.string.diagnostics_ts_rate),
            debug.transportBps?.let { stringResource(R.string.diagnostics_mbps, it / 1_000_000.0) } ?: dash,
            bad = false,
        )
    }
}

@Composable
private fun Counter(label: String, value: String, bad: Boolean) {
    val colors = MaterialTheme.sightlineColors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // Not colour alone: a non-zero count also gets the warning icon.
            if (bad) Icon(Icons.Rounded.Warning, contentDescription = stringResource(R.string.diagnostics_counter_nonzero), tint = colors.healthMarginal, modifier = Modifier.size(14.dp))
            Text(value, style = Numbers.info, color = if (bad) colors.healthMarginal else colors.textPrimary)
        }
    }
}

@Composable
private fun RawReplies(probes: List<Probe>) {
    val colors = MaterialTheme.sightlineColors
    val small = Numbers.info.copy(fontSize = Numbers.info.fontSize * 0.8f)
    Column(
        Modifier.fillMaxWidth().background(colors.bg, MaterialTheme.shapes.small).padding(10.dp).horizontalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        probes.forEach { p ->
            Text(p.name, style = small, color = colors.textSecondary)
            Text(rawText(p.result, stringResource(R.string.diagnostics_no_answer)), style = small)
        }
    }
}

@Composable
private fun HolderChip(holder: TunerHolder) {
    val colors = MaterialTheme.sightlineColors
    val (text, tint, icon) = when (holder) {
        TunerHolder.Idle -> Triple(stringResource(R.string.diagnostics_holder_idle), colors.healthGood, Icons.Rounded.CheckCircle)
        TunerHolder.ThisApp -> Triple(stringResource(R.string.diagnostics_holder_app), colors.accent, Icons.Rounded.RadioButtonChecked)
        is TunerHolder.ThisPhone -> Triple(stringResource(R.string.diagnostics_holder_phone), colors.healthMarginal, Icons.Rounded.PhoneAndroid)
        is TunerHolder.Other -> Triple(
            holder.address?.let { stringResource(R.string.diagnostics_holder_other, it) } ?: stringResource(R.string.diagnostics_holder_other_unknown),
            colors.healthNoLock,
            Icons.Rounded.RadioButtonChecked,
        )
        TunerHolder.TunedNoHolder -> Triple(stringResource(R.string.diagnostics_holder_nobody), colors.healthMarginal, Icons.Rounded.Warning)
        TunerHolder.Unknown -> Triple(stringResource(R.string.diagnostics_holder_unknown), colors.outline, Icons.Rounded.HelpOutline)
    }
    Row(
        Modifier.background(tint.copy(alpha = 0.12f), MaterialTheme.shapes.small).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

@Composable
private fun holderNote(holder: TunerHolder): String? = when (holder) {
    is TunerHolder.ThisPhone ->
        stringResource(if (holder.staleKey) R.string.diagnostics_phone_stale_key else R.string.diagnostics_phone_no_key)
    TunerHolder.TunedNoHolder -> stringResource(R.string.diagnostics_nobody_note)
    else -> null
}

private fun List<Probe>.valueOf(name: String) = (firstOrNull { it.name == name }?.result as? ControlResult.Value)?.value?.trim()

private fun rawText(result: ControlResult, noAnswer: String) = when (result) {
    is ControlResult.Value -> result.value.trimEnd().ifEmpty { "\"\"" }
    is ControlResult.Rejected -> result.error.message
    is ControlResult.Unreachable -> noAnswer
}

private fun time(ms: Long, zone: ZoneId) = DateTimeFormatter.ofPattern("HH:mm:ss").format(Instant.ofEpochMilli(ms).atZone(zone))

/** Plain text for a bug report: what the screen shows plus every raw reply. Holds no DeviceAuth: it is never read. */
internal fun diagnosticsReport(context: Context, d: Diagnostics, appVersion: String, zone: ZoneId): String = buildString {
    val noAnswer = context.getString(R.string.diagnostics_no_answer)
    appendLine(context.getString(R.string.diagnostics_report_title, appVersion))
    appendLine(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(d.readAtMs).atZone(zone)))
    appendLine()
    appendLine("${d.device.friendlyName} · ${d.device.model} · ${d.device.address}")
    appendLine("discover.json FirmwareVersion: ${d.info?.firmwareVersion ?: noAnswer}")
    appendLine("discover.json UpgradeAvailable: ${d.info?.upgradeAvailable ?: "-"}")
    appendLine(context.getString(R.string.diagnostics_report_phone, d.snapshot.localAddress ?: noAnswer))
    appendLine(context.getString(R.string.diagnostics_report_keys, d.rememberedKeys.sorted().joinToString().ifEmpty { "-" }))
    d.snapshot.system.forEach { appendLine("${it.name}: ${rawText(it.result, noAnswer).replace("\n", "\n    ")}") }
    d.snapshot.tuners.forEach { t ->
        appendLine()
        appendLine("== /tuner${t.tuner} · ${d.holders[t.tuner]}")
        t.probes.forEach { appendLine("${it.name}: ${rawText(it.result, noAnswer).replace("\n", "\n    ")}") }
    }
}

private fun shareReport(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, context.getString(R.string.diagnostics_share)))
}
