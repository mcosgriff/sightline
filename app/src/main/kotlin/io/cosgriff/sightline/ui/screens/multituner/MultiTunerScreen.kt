package io.cosgriff.sightline.ui.screens.multituner

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.cosgriff.sightline.R
import io.cosgriff.sightline.analysis.Health
import io.cosgriff.sightline.analysis.SignalSample
import io.cosgriff.sightline.analysis.Thresholds
import io.cosgriff.sightline.analysis.assess
import io.cosgriff.sightline.data.device.Channel
import io.cosgriff.sightline.data.device.TunerUse
import io.cosgriff.sightline.data.live.LiveState
import io.cosgriff.sightline.protocol.Occupancy
import io.cosgriff.sightline.ui.components.ArcGauge
import io.cosgriff.sightline.ui.components.HealthChip
import io.cosgriff.sightline.ui.components.KeepScreenOn
import io.cosgriff.sightline.ui.components.LocalNetworkCard
import io.cosgriff.sightline.ui.components.rememberLocalNetworkAccess
import io.cosgriff.sightline.ui.components.color
import io.cosgriff.sightline.ui.screens.Selection
import io.cosgriff.sightline.ui.theme.Numbers
import io.cosgriff.sightline.ui.theme.sightlineColors

data class MultiTunerActions(
    val onBack: () -> Unit,
    val onTune: (tuner: Int, Channel) -> Unit,
    val onStop: (tuner: Int) -> Unit,
    val onRetry: (tuner: Int) -> Unit,
)

@Composable
fun MultiTunerScreen(onBack: () -> Unit, viewModel: MultiTunerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val network = rememberLocalNetworkAccess()
    if (network.granted) {
        LifecycleStartEffect(viewModel) {
            viewModel.start()
            onStopOrDispose { viewModel.stop() }
        }
    }
    if (settings.keepScreenOn && state.cells.any { it is TunerCell.On }) KeepScreenOn()
    MultiTunerContent(
        state = state,
        networkCard = if (network.granted) null else ({ LocalNetworkCard(network) }),
        actions = MultiTunerActions(onBack, viewModel::tune, viewModel::stopTuner, viewModel::retry),
    )
}

/**
 * Multi-tuner (SPEC Screens › 5): a grid of compact gauges, two across, one per tuner. A tap on a cell picks its
 * channel or stops it; a tuner another client holds says who, and offers nothing.
 */
@Composable
fun MultiTunerContent(
    state: MultiTunerUiState,
    networkCard: (@Composable () -> Unit)?,
    actions: MultiTunerActions,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.sightlineColors
    val ready = state.selection as? Selection.Ready
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.tuners_back))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.tuners_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                ready?.let { Text(it.device.friendlyName, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary) }
            }
        }
        if (networkCard != null) {
            Column(Modifier.padding(16.dp)) { networkCard() }
            return@Column
        }
        when (val s = state.selection) {
            null -> Unit
            Selection.NoDevice -> Note(stringResource(R.string.tuners_no_device))
            is Selection.NoChannels -> Note(stringResource(R.string.tuners_no_channels))
            is Selection.Ready -> {
                Note(stringResource(R.string.tuners_hint))
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (row in state.cells.chunked(2)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            for (cell in row) Cell(cell, s.channels, state.thresholds, actions, Modifier.weight(1f))
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.sightlineColors.textSecondary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
    )
}

@Composable
private fun Cell(cell: TunerCell, channels: List<Channel>, thresholds: Thresholds, actions: MultiTunerActions, modifier: Modifier) {
    val colors = MaterialTheme.sightlineColors
    var menu by remember { mutableStateOf(false) }
    // A tuner another client holds, or one that doesn't answer, offers nothing: Sightline never takes a busy tuner.
    val usable = cell is TunerCell.On || (cell as TunerCell.Off).use == TunerUse.Idle
    val failed = (cell as? TunerCell.On)?.live.let { it is LiveState.Lost || it is LiveState.UnknownChannel }
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(min = 196.dp)
                .background(colors.surface, MaterialTheme.shapes.medium)
                .border(1.dp, colors.outline, MaterialTheme.shapes.medium)
                .then(
                    if (usable) {
                        Modifier.clickable(onClickLabel = stringResource(R.string.tuners_cell_action), role = Role.Button) {
                            if (failed) actions.onRetry(cell.tuner) else menu = true
                        }
                    } else {
                        Modifier
                    },
                )
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stringResource(R.string.tuners_tuner, cell.tuner), style = MaterialTheme.typography.labelLarge, color = colors.textSecondary)
            when (cell) {
                is TunerCell.Off -> OffCell(cell)
                is TunerCell.On -> OnCell(cell, thresholds)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (cell is TunerCell.On) {
                DropdownMenuItem(text = { Text(stringResource(R.string.tuners_stop)) }, onClick = {
                    menu = false
                    actions.onStop(cell.tuner)
                })
                HorizontalDivider(color = colors.outline)
            }
            for (c in channels) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.tuners_channel, c.guideNumber, c.guideName), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        menu = false
                        actions.onTune(cell.tuner, c)
                    },
                )
            }
        }
    }
}

@Composable
private fun OffCell(cell: TunerCell.Off) {
    val colors = MaterialTheme.sightlineColors
    val (title, body) = when (val use = cell.use) {
        TunerUse.Idle -> stringResource(R.string.tuners_off_idle) to
            stringResource(if (cell.spare) R.string.tuners_off_spare else R.string.tuners_off_tap)
        TunerUse.Sightline -> stringResource(R.string.tuners_off_idle) to stringResource(R.string.tuners_off_tap)
        is TunerUse.Other -> stringResource(R.string.tuners_off_busy) to
            (use.holder?.let { stringResource(R.string.tuners_off_busy_by, it) } ?: stringResource(R.string.tuners_off_busy_other))
        TunerUse.Unknown -> stringResource(R.string.tuners_off_unknown) to stringResource(R.string.tuners_off_unknown_body)
    }
    Text(title, style = MaterialTheme.typography.titleMedium)
    Text(body, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
}

@Composable
private fun ColumnScope.OnCell(cell: TunerCell.On, thresholds: Thresholds) {
    val colors = MaterialTheme.sightlineColors
    Text(stringResource(R.string.tuners_channel, cell.channel.guideNumber, cell.channel.guideName), style = Numbers.channel, maxLines = 1, overflow = TextOverflow.Ellipsis)
    when (val live = cell.live) {
        is LiveState.Watching -> {
            val s = live.status
            val health = s?.let { thresholds.assess(SignalSample(it.isLocked, it.ss, it.snq, it.seq)).health }
            val snq = s?.takeIf { it.isLocked }?.snq
            ArcGauge(
                value = snq,
                color = (health ?: Health.NO_LOCK).color(),
                trackColor = colors.outline,
                markColor = colors.textPrimary,
                holeColor = colors.surface,
                stroke = 9.dp,
                description = snq?.let { stringResource(R.string.tuners_gauge_description, cell.tuner, it) } ?: stringResource(R.string.tuners_gauge_none, cell.tuner),
                threshold = thresholds.snqGood,
                modifier = Modifier.align(Alignment.CenterHorizontally).size(width = 104.dp, height = 84.dp),
            ) {
                Text(snq?.toString() ?: stringResource(R.string.gauge_no_value), style = Numbers.gaugeSmall, modifier = Modifier.offset(y = (-4).dp))
            }
            if (s != null && s.isLocked) {
                Text(stringResource(R.string.tuners_numbers, s.ss, s.seq), style = Numbers.inline, color = colors.textSecondary, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
            health?.let { HealthChip(it, Modifier.align(Alignment.CenterHorizontally)) }
        }
        null, is LiveState.Claiming -> Status(stringResource(R.string.tuners_claiming))
        is LiveState.AllBusy -> Status(
            when (val o = live.occupancy[cell.tuner]) {
                Occupancy.Idle -> stringResource(R.string.tuners_cant_tune)
                is Occupancy.Busy -> o.holder?.let { stringResource(R.string.tuners_off_busy_by, it) } ?: stringResource(R.string.tuners_off_busy_other)
                else -> stringResource(R.string.tuners_off_unknown_body)
            } + "\n" + stringResource(R.string.tuners_waiting, live.retryEvery.inWholeSeconds.toInt()),
        )
        is LiveState.Lost -> Status(stringResource(R.string.tuners_lost))
        is LiveState.UnknownChannel -> Status(stringResource(R.string.tuners_unknown_channel))
        is LiveState.Unreachable -> Status(stringResource(R.string.tuners_unreachable))
    }
}

@Composable
private fun Status(text: String) {
    Spacer(Modifier.height(8.dp))
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.sightlineColors.textSecondary)
}
