package io.cosgriff.sightline.ui.screens.sweep

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.cosgriff.sightline.R
import io.cosgriff.sightline.ui.components.ChannelDeltaRow
import io.cosgriff.sightline.analysis.Health
import io.cosgriff.sightline.analysis.SweptChannel
import io.cosgriff.sightline.data.device.Channel
import io.cosgriff.sightline.data.sweep.SavedSweep
import io.cosgriff.sightline.data.sweep.SweepInfo
import io.cosgriff.sightline.data.sweep.SweepPhase
import io.cosgriff.sightline.ui.components.ChannelRangeBars
import io.cosgriff.sightline.ui.components.KeepScreenOn
import io.cosgriff.sightline.ui.components.LocalNetworkCard
import io.cosgriff.sightline.ui.components.RangeRow
import io.cosgriff.sightline.ui.components.bandLabel
import io.cosgriff.sightline.ui.components.color
import io.cosgriff.sightline.ui.components.rememberLocalNetworkAccess
import io.cosgriff.sightline.ui.screens.Selection
import io.cosgriff.sightline.ui.theme.Numbers
import io.cosgriff.sightline.ui.theme.sightlineColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

data class SweepActions(
    val onBack: () -> Unit,
    val onSweepAgain: () -> Unit,
    val onCompare: (Long?) -> Unit,
    val onCloseCompare: () -> Unit,
)

@Composable
fun SweepScreen(onBack: () -> Unit, viewModel: SweepViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val network = rememberLocalNetworkAccess()
    if (network.granted) {
        LifecycleStartEffect(viewModel) {
            viewModel.start()
            onStopOrDispose { viewModel.stop() }
        }
    }
    if (settings.keepScreenOn && state.run is SweepRun.Running) KeepScreenOn()
    BackHandler(enabled = state.compare != null) { viewModel.closeCompare() }
    SweepContent(
        state = state,
        zone = ZoneId.systemDefault(),
        networkCard = if (network.granted) null else ({ LocalNetworkCard(network) }),
        actions = SweepActions(onBack, viewModel::sweepAgain, viewModel::compare, viewModel::closeCompare),
    )
}

@Composable
fun SweepContent(
    state: SweepUiState,
    zone: ZoneId,
    networkCard: (@Composable () -> Unit)?,
    actions: SweepActions,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.sightlineColors
    val result = state.result
    val compare = state.compare
    Column(modifier.fillMaxSize().background(colors.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
        when {
            networkCard != null -> {
                Header(stringResource(R.string.sweep_title), null, actions.onBack)
                Column(Modifier.padding(16.dp)) { networkCard() }
            }
            state.selection == null -> Header(stringResource(R.string.sweep_title), null, actions.onBack)
            state.selection !is Selection.Ready -> {
                Header(stringResource(R.string.sweep_title), null, actions.onBack)
                Text(
                    stringResource(R.string.sweep_no_channels),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(20.dp),
                )
            }
            compare != null && result != null -> Compare(result.saved, compare, state.sweeps, zone, actions)
            state.run is SweepRun.Running -> Running(state.run, actions.onBack)
            state.run == SweepRun.Failed -> Failed(actions)
            result != null -> Results(result, state.thresholds.snqGood, zone, actions)
            else -> Header(stringResource(R.string.sweep_title), null, actions.onBack)
        }
    }
}

@Composable
private fun Header(title: String, subtitle: String?, onBack: () -> Unit) {
    val colors = MaterialTheme.sightlineColors
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.sweep_back))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
    }
}

@Composable
private fun ColumnScope.Running(run: SweepRun.Running, onBack: () -> Unit) {
    val colors = MaterialTheme.sightlineColors
    val progress = run.progress
    Header(
        stringResource(R.string.sweep_running_title),
        pluralStringResource(R.plurals.sweep_running_subtitle, run.channelCount, run.channelCount, (run.dwellMs / 1000).toInt()),
        onBack,
    )
    Column(
        Modifier.weight(1f).fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { progress?.fraction ?: 0f },
                modifier = Modifier.size(200.dp),
                color = colors.accent,
                trackColor = colors.surface,
                strokeWidth = 10.dp,
                gapSize = 0.dp,
            )
            Text(
                if (progress != null) stringResource(R.string.sweep_progress_count, progress.index + 1, progress.total) else "",
                style = Numbers.count,
            )
        }
        if (progress != null) {
            Text(
                stringResource(R.string.sweep_progress_channel, progress.channel.guideNumber, progress.channel.guideName),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 20.dp),
            )
            val phase = when (val p = progress.phase) {
                SweepPhase.Claiming -> stringResource(R.string.sweep_phase_claiming)
                is SweepPhase.Measuring -> p.latest?.takeIf { it.isLocked }?.let { stringResource(R.string.sweep_phase_reading, p.tuner, it.snq) }
                    ?: stringResource(R.string.sweep_phase_tuned, p.tuner)
                is SweepPhase.AllBusy -> stringResource(R.string.sweep_phase_all_busy, p.retryEvery.inWholeSeconds.toInt())
                SweepPhase.Unreachable -> stringResource(R.string.sweep_phase_unreachable)
            }
            Text(
                phase,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
    OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp)) {
        Text(stringResource(R.string.sweep_cancel))
    }
}

@Composable
private fun Failed(actions: SweepActions) {
    Header(stringResource(R.string.sweep_title), null, actions.onBack)
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.sweep_failed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.sightlineColors.textSecondary)
        Button(onClick = actions.onSweepAgain) { Text(stringResource(R.string.sweep_again)) }
    }
}

@Composable
private fun ColumnScope.Results(result: SweepResult, threshold: Int, zone: ZoneId, actions: SweepActions) {
    val colors = MaterialTheme.sightlineColors
    val info = result.saved.info
    val summary = result.saved.summary
    val measured = summary.channels.count { it.measured }
    Header(
        stringResource(R.string.sweep_results_title),
        pluralStringResource(R.plurals.sweep_results_subtitle, measured, measured, ((info.dwellMs ?: 0) / 1000).toInt(), time(info.endedAt ?: info.startedAt, zone)),
        actions.onBack,
    )
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Count(R.string.sweep_count_good, summary.good, Health.GOOD.color(), Modifier.weight(1f))
            Count(R.string.sweep_count_marginal, summary.marginal, Health.MARGINAL.color(), Modifier.weight(1f))
            Count(R.string.sweep_count_poor, summary.poor, Health.POOR.color(), Modifier.weight(1f))
        }
        val comparison = result.comparison
        val previous = result.previous
        if (comparison != null && previous != null) {
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp).background(colors.surface, MaterialTheme.shapes.medium).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.sweep_vs, date(previous.info.startedAt, zone)), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(pluralStringResource(R.plurals.sweep_better, comparison.better, comparison.better), style = MaterialTheme.typography.titleSmall, color = colors.healthGood)
                Text(stringResource(R.string.sweep_separator), style = MaterialTheme.typography.titleSmall, color = colors.textSecondary)
                Text(pluralStringResource(R.plurals.sweep_worse, comparison.worse, comparison.worse), style = MaterialTheme.typography.titleSmall, color = colors.healthPoor)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp)) {
            Text(stringResource(R.string.sweep_list_left), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.sweep_list_right), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
        ChannelRangeBars(summary.worstFirst.map { rangeRow(it, result.saved.channels[it.guideNumber]) }, threshold)
        Text(
            stringResource(R.string.sweep_threshold_note, threshold),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            modifier = Modifier.padding(vertical = 12.dp),
        )
    }
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = { actions.onCompare(null) }, enabled = result.previous != null, modifier = Modifier.weight(1f).height(52.dp)) {
            Text(stringResource(R.string.sweep_compare))
        }
        Button(onClick = actions.onSweepAgain, modifier = Modifier.weight(1f).height(52.dp)) { Text(stringResource(R.string.sweep_again)) }
    }
}

@Composable
private fun Count(label: Int, count: Int, tint: Color, modifier: Modifier) {
    Column(
        modifier.background(MaterialTheme.sightlineColors.surface, MaterialTheme.shapes.medium).padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.sightlineColors.textSecondary)
        Text(count.toString(), style = Numbers.count, color = tint)
    }
}

@Composable
private fun rangeRow(c: SweptChannel, channel: Channel?): RangeRow {
    val rf = channel?.rfChannel
    val band = channel?.band?.let { bandLabel(it) }
    val subtitle = when {
        rf != null && band != null -> stringResource(R.string.sweep_rf_band, rf, band)
        rf != null -> stringResource(R.string.sweep_rf, rf)
        else -> channel?.guideName ?: ""
    }
    return RangeRow(c.guideNumber, c.guideNumber, subtitle, c.minSnq, c.medianSnq, c.maxSnq, c.health)
}

@Composable
private fun ColumnScope.Compare(
    current: SavedSweep,
    compare: SweepCompare,
    sweeps: List<SweepInfo>,
    zone: ZoneId,
    actions: SweepActions,
) {
    val colors = MaterialTheme.sightlineColors
    Header(
        stringResource(R.string.sweep_compare_title),
        stringResource(R.string.sweep_compare_subtitle, date(compare.other.info.startedAt, zone), time(compare.other.info.startedAt, zone)),
        actions.onCloseCompare,
    )
    var picking by remember { mutableStateOf(false) }
    Box(Modifier.padding(horizontal = 8.dp)) {
        TextButton(onClick = { picking = true }) { Text(stringResource(R.string.sweep_compare_pick)) }
        DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
            sweeps.filter { it.id != current.info.id }.forEach { s ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sweep_compare_option, date(s.startedAt, zone), time(s.startedAt, zone))) },
                    onClick = {
                        picking = false
                        actions.onCompare(s.id)
                    },
                )
            }
        }
    }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(stringResource(R.string.sweep_compare_channel), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.sweep_compare_before), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, modifier = Modifier.width(56.dp), textAlign = TextAlign.End)
            Text(stringResource(R.string.sweep_compare_after), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, modifier = Modifier.width(56.dp), textAlign = TextAlign.End)
            Text(stringResource(R.string.sweep_compare_change), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, modifier = Modifier.width(96.dp), textAlign = TextAlign.End)
        }
        HorizontalDivider(color = colors.outline)
        compare.comparison.deltas.forEach { d ->
            ChannelDeltaRow(d, current.channels[d.guideNumber] ?: compare.other.channels[d.guideNumber])
            HorizontalDivider(color = colors.outline)
        }
    }
}

private fun time(ms: Long, zone: ZoneId): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()).format(Instant.ofEpochMilli(ms).atZone(zone))

private fun date(ms: Long, zone: ZoneId): String {
    val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "MMMd")
    return DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).format(Instant.ofEpochMilli(ms).atZone(zone))
}
