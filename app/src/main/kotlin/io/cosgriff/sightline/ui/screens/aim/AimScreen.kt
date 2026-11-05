package io.cosgriff.sightline.ui.screens.aim

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.cosgriff.sightline.R
import io.cosgriff.sightline.analysis.Health
import io.cosgriff.sightline.analysis.Tone
import io.cosgriff.sightline.analysis.SignalSample
import io.cosgriff.sightline.analysis.Thresholds
import io.cosgriff.sightline.analysis.assess
import io.cosgriff.sightline.analysis.roundDegrees
import io.cosgriff.sightline.data.device.Channel
import io.cosgriff.sightline.data.live.LiveState
import io.cosgriff.sightline.data.settings.Settings
import io.cosgriff.sightline.protocol.RfChannels
import io.cosgriff.sightline.ui.components.HealthChip
import io.cosgriff.sightline.ui.components.KeepScreenOn
import io.cosgriff.sightline.ui.components.rememberHomeFix
import io.cosgriff.sightline.ui.components.LocalNetworkCard
import io.cosgriff.sightline.ui.components.PolarAim
import io.cosgriff.sightline.ui.components.bandLabel
import io.cosgriff.sightline.ui.components.color
import io.cosgriff.sightline.ui.components.label
import io.cosgriff.sightline.ui.components.rememberLocalNetworkAccess
import io.cosgriff.sightline.ui.screens.Selection
import io.cosgriff.sightline.ui.theme.Numbers
import io.cosgriff.sightline.ui.theme.SightlineTheme
import io.cosgriff.sightline.ui.theme.ThemeMode
import io.cosgriff.sightline.ui.theme.sightlineColors

/** What Aim's controls change. */
data class AimActions(
    val onBack: () -> Unit,
    val onTone: (Boolean) -> Unit,
    val onHaptics: (Boolean) -> Unit,
    val onResetPeak: () -> Unit,
    val onRetry: () -> Unit,
    val onSecond: (Channel?) -> Unit,
    val onTowers: (List<Int>) -> Unit,
    val onTrueNorth: () -> Unit,
)

@Composable
fun AimScreen(onBack: () -> Unit, viewModel: AimViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val network = rememberLocalNetworkAccess()
    val context = LocalContext.current
    if (network.granted) {
        LifecycleStartEffect(viewModel) {
            viewModel.start()
            onStopOrDispose { viewModel.stop() }
        }
    }
    if (settings.keepScreenOn) KeepScreenOn()

    // Tone: plays while the screen is up and the toggle is on, following each reading.
    val tone = remember { TonePlayer() }
    val status = state.status
    LaunchedEffect(status) { tone.update(status?.let { Tone.of(SignalSample(it.isLocked, it.ss, it.snq, it.seq)) }) }
    if (settings.aimTone) {
        LifecycleStartEffect(tone) {
            tone.start()
            onStopOrDispose { tone.stop() }
        }
    }
    val haptics = remember(context) { AimHaptics(context) }
    LaunchedEffect(settings.aimHaptics) {
        if (settings.aimHaptics) viewModel.events.collect(haptics::play)
    }
    val trueNorth = rememberHomeFix(viewModel::saveHome)

    SightlineTheme(ThemeMode.TrueBlack) {
        AimContent(
            state = state,
            settings = settings,
            networkCard = if (network.granted) null else ({ LocalNetworkCard(network) }),
            actions = AimActions(
                onBack = onBack,
                onTone = viewModel::setTone,
                onHaptics = viewModel::setHaptics,
                onResetPeak = viewModel::resetPeak,
                onRetry = viewModel::retry,
                onSecond = viewModel::setSecond,
                onTowers = viewModel::setTowers,
                onTrueNorth = trueNorth,
            ),
        )
    }
}

@Composable
fun AimContent(
    state: AimUiState,
    settings: Settings,
    networkCard: (@Composable () -> Unit)?,
    actions: AimActions,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.sightlineColors
    val ready = state.selection as? Selection.Ready
    Column(modifier.fillMaxSize().background(colors.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Header(state, ready, actions.onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            when {
                networkCard != null -> Column(Modifier.padding(16.dp)) { networkCard() }
                state.selection == null -> Unit
                ready == null -> Text(
                    stringResource(R.string.aim_no_channel),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(20.dp),
                )
                else -> Body(state, settings, ready, actions)
            }
        }
        Controls(settings, actions)
    }
}

@Composable
private fun Header(state: AimUiState, ready: Selection.Ready?, onBack: () -> Unit) {
    val colors = MaterialTheme.sightlineColors
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.aim_back))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            val title = ready?.let { stringResource(R.string.aim_title_channel, it.channel.guideNumber, it.channel.guideName) }
                ?: stringResource(R.string.aim_title)
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.semantics { heading() })
            if (ready != null) {
                val rf = state.status?.let { RfChannels.of(it.channel) }
                val number = rf?.number ?: ready.channel.rfChannel
                val band = (rf?.band ?: ready.channel.band)?.let { bandLabel(it) }
                val tuner = (state.live as? LiveState.Watching)?.tuner
                val where = when {
                    number != null && band != null && tuner != null -> stringResource(R.string.aim_subtitle_rf_tuner, number, band, tuner)
                    number != null && band != null -> stringResource(R.string.aim_subtitle_rf, number, band)
                    tuner != null -> stringResource(R.string.aim_subtitle_tuner, tuner)
                    else -> stringResource(R.string.aim_subtitle)
                }
                Text(where, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Body(state: AimUiState, settings: Settings, ready: Selection.Ready, actions: AimActions) {
    val colors = MaterialTheme.sightlineColors
    val t = state.thresholds
    val status = state.status
    val locked = status?.isLocked == true
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.aim_signal_quality), style = MaterialTheme.typography.labelLarge, color = colors.textSecondary)
        val health = when {
            status == null -> null
            !locked -> Health.NO_LOCK
            else -> t.snq(status.snq)
        }
        val described = when {
            health == null -> stringResource(R.string.aim_waiting)
            status != null && locked -> stringResource(R.string.aim_value_description, status.snq, stringResource(health.label))
            else -> stringResource(health.label)
        }
        Text(
            if (locked) status.snq.toString() else stringResource(R.string.aim_no_value),
            style = Numbers.aim,
            color = health?.color() ?: colors.healthNoLock,
            modifier = Modifier.semantics { contentDescription = described },
        )
        if (health != null) HealthChip(health)
        BestLine(state)
    }
    Watching(state, actions.onRetry)
    SeqBar(status?.takeIf { it.isLocked }?.seq, t)
    SecondRow(state, ready, t, actions.onSecond)
    Compass(state, settings, actions)
}

@Composable
private fun BestLine(state: AimUiState) {
    val colors = MaterialTheme.sightlineColors
    val peak = state.trace.peak
    val now = state.heading?.let { roundDegrees(it.degrees) }
    val peakHeading = peak?.headingDeg
    val best = when {
        peak == null -> stringResource(R.string.aim_no_peak)
        peakHeading != null -> stringResource(R.string.aim_best_at, peak.snq, peakHeading)
        else -> stringResource(R.string.aim_best, peak.snq)
    }
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(best, style = MaterialTheme.typography.bodyLarge)
        if (now != null) Text(stringResource(R.string.aim_now, now), style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary)
    }
}

/** What's going on when Aim isn't reading: claiming, all busy, lost, refused, unreachable. */
@Composable
private fun Watching(state: AimUiState, onRetry: () -> Unit) {
    val colors = MaterialTheme.sightlineColors
    val text = when (val live = state.live) {
        null, is LiveState.Claiming -> stringResource(R.string.aim_claiming)
        is LiveState.Watching -> if (live.missedPolls >= 3) stringResource(R.string.aim_not_answering) else null
        is LiveState.AllBusy -> stringResource(R.string.aim_all_busy, live.retryEvery.inWholeSeconds.toInt())
        is LiveState.Lost -> stringResource(R.string.aim_lost, live.tuner)
        is LiveState.UnknownChannel -> stringResource(R.string.aim_unknown_channel)
        is LiveState.Unreachable -> stringResource(R.string.aim_unreachable)
    } ?: return
    val retry = state.live is LiveState.Lost || state.live is LiveState.UnknownChannel
    Row(
        Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 10.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, modifier = Modifier.weight(1f))
        if (retry) OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.aim_retry)) }
    }
}

@Composable
private fun SeqBar(seq: Int?, t: Thresholds) {
    val colors = MaterialTheme.sightlineColors
    Column(Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            Text(stringResource(R.string.aim_symbol_quality), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, modifier = Modifier.weight(1f))
            Text(seq?.toString() ?: stringResource(R.string.aim_no_value), style = Numbers.inline)
        }
        val health = seq?.let { t.seq(it) }
        val described = if (seq != null && health != null) {
            stringResource(R.string.aim_seq_description, seq, stringResource(health.label))
        } else {
            stringResource(R.string.aim_seq_none)
        }
        LinearProgressIndicator(
            progress = { (seq ?: 0) / 100f },
            color = health?.color() ?: colors.healthNoLock,
            trackColor = colors.surface,
            drawStopIndicator = {},
            gapSize = 0.dp,
            modifier = Modifier.fillMaxWidth().height(8.dp).semantics { contentDescription = described },
        )
    }
}

@Composable
private fun SecondRow(state: AimUiState, ready: Selection.Ready, t: Thresholds, onSecond: (Channel?) -> Unit) {
    val colors = MaterialTheme.sightlineColors
    var open by remember { mutableStateOf(false) }
    val second = state.second
    Box(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { open = true }.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (second == null) {
                Text(stringResource(R.string.aim_add_second), style = MaterialTheme.typography.bodySmall, color = colors.accent)
            } else {
                Text(
                    stringResource(R.string.aim_second, second.channel.guideNumber),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                val s = (second.live as? LiveState.Watching)?.status
                val (text, color) = when {
                    s == null -> when (second.live) {
                        is LiveState.AllBusy -> stringResource(R.string.aim_second_busy) to colors.textSecondary
                        is LiveState.Lost, is LiveState.UnknownChannel, is LiveState.Unreachable ->
                            stringResource(R.string.aim_second_stopped) to colors.textSecondary
                        else -> stringResource(R.string.aim_second_tuning) to colors.textSecondary
                    }
                    !s.isLocked -> stringResource(Health.NO_LOCK.label) to Health.NO_LOCK.color()
                    else -> {
                        val h = t.assess(SignalSample(true, s.ss, s.snq, s.seq)).health
                        stringResource(R.string.aim_second_value, s.snq, stringResource(h.label)) to h.color()
                    }
                }
                Text(text, style = Numbers.inline, color = color)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (second != null) {
                DropdownMenuItem(text = { Text(stringResource(R.string.aim_second_none)) }, onClick = {
                    open = false
                    onSecond(null)
                })
            }
            ready.channels.filter { it.guideNumber != ready.channel.guideNumber }.forEach { channel ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.aim_second_option, channel.guideNumber, channel.guideName)) },
                    onClick = {
                        open = false
                        onSecond(channel)
                    },
                )
            }
        }
    }
}

@Composable
private fun Compass(state: AimUiState, settings: Settings, actions: AimActions) {
    val colors = MaterialTheme.sightlineColors
    var editing by rememberSaveable { mutableStateOf(false) }
    val peak = state.trace.peak
    val peakHeading = peak?.headingDeg
    val description = when {
        !state.compass -> stringResource(R.string.aim_plot_no_compass)
        peak != null && peakHeading != null -> stringResource(R.string.aim_plot_description_peak, peak.snq, peakHeading)
        else -> stringResource(R.string.aim_plot_description)
    }
    PolarAim(
        points = state.trace.points,
        headingDeg = state.heading?.degrees,
        best = peak,
        towers = settings.towerBearings,
        description = description,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 8.dp).aspectRatio(1f).widthIn(max = 360.dp),
    )
    // Legend.
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendItem(stringResource(R.string.aim_legend_heading)) { Box(Modifier.width(14.dp).height(2.dp).background(colors.textPrimary)) }
        LegendItem(stringResource(R.string.aim_legend_best)) { Box(Modifier.size(8.dp).background(colors.healthGood, CircleShape)) }
        settings.towerBearings.forEachIndexed { i, bearing ->
            LegendItem(stringResource(R.string.aim_legend_tower, towerLetter(i), bearing)) {
                Box(Modifier.width(10.dp).height(3.dp).background(colors.healthMarginal))
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { editing = true }) {
            Text(stringResource(if (settings.towerBearings.isEmpty()) R.string.aim_towers_add else R.string.aim_towers_edit))
        }
        val heading = state.heading
        when {
            !state.compass -> Text(stringResource(R.string.aim_no_compass), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            heading == null -> Text(stringResource(R.string.aim_hold_level), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            !heading.trueNorth -> TextButton(onClick = actions.onTrueNorth) { Text(stringResource(R.string.aim_use_true_north)) }
            else -> Text(stringResource(R.string.aim_true_north), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
    }
    if (editing) {
        TowersDialog(settings.towerBearings, onDismiss = { editing = false }) {
            editing = false
            actions.onTowers(it)
        }
    }
}

@Composable
private fun LegendItem(text: String, swatch: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        swatch()
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.sightlineColors.textSecondary)
    }
}

@Composable
private fun towerLetter(i: Int) = stringResource(
    when (i) {
        0 -> R.string.aim_tower_a
        1 -> R.string.aim_tower_b
        else -> R.string.aim_tower_c
    },
)

@Composable
private fun TowersDialog(bearings: List<Int>, onDismiss: () -> Unit, onSave: (List<Int>) -> Unit) {
    val fields = remember { List(Settings.MAX_TOWERS) { i -> mutableStateOf(bearings.getOrNull(i)?.toString().orEmpty()) } }
    val valid = fields.all { f -> f.value.isBlank() || f.value.trim().toIntOrNull()?.let { it in 0..359 } == true }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.aim_towers_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.aim_towers_body), style = MaterialTheme.typography.bodySmall)
                fields.forEachIndexed { i, field ->
                    OutlinedTextField(
                        value = field.value,
                        onValueChange = { v -> field.value = v.filter(Char::isDigit).take(3) },
                        label = { Text(stringResource(R.string.aim_towers_field, towerLetter(i))) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onSave(fields.mapNotNull { it.value.trim().toIntOrNull() }) }) {
                Text(stringResource(R.string.aim_towers_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.aim_towers_cancel)) } },
    )
}

@Composable
private fun Controls(settings: Settings, actions: AimActions) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Toggle(
            on = settings.aimTone,
            label = stringResource(if (settings.aimTone) R.string.aim_tone_on else R.string.aim_tone_off),
            icon = { tint -> Icon(if (settings.aimTone) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff, null, tint = tint, modifier = Modifier.size(18.dp)) },
            onClick = { actions.onTone(!settings.aimTone) },
            modifier = Modifier.weight(1f),
        )
        Toggle(
            on = settings.aimHaptics,
            label = stringResource(if (settings.aimHaptics) R.string.aim_haptics_on else R.string.aim_haptics_off),
            icon = null,
            onClick = { actions.onHaptics(!settings.aimHaptics) },
            modifier = Modifier.weight(1f),
        )
        Toggle(on = false, label = stringResource(R.string.aim_reset_peak), icon = null, onClick = actions.onResetPeak, modifier = Modifier.weight(1f), toggle = false)
    }
}

/** A 52dp pill: filled accent when on (the mockup's "Tone on"), outlined otherwise. */
@Composable
private fun Toggle(
    on: Boolean,
    label: String,
    icon: (@Composable (Color) -> Unit)?,
    onClick: () -> Unit,
    modifier: Modifier,
    toggle: Boolean = true,
) {
    val colors = MaterialTheme.sightlineColors
    val shape = MaterialTheme.shapes.small
    val fill = if (on) colors.accent else colors.bar
    val ink = if (on) MaterialTheme.colorScheme.onPrimary else colors.textPrimary
    Row(
        modifier
            .height(52.dp)
            .background(fill, shape)
            .then(if (on) Modifier else Modifier.border(1.dp, colors.outline, shape))
            .clickable(role = if (toggle) Role.Switch else Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.invoke(ink)
        Text(label, style = MaterialTheme.typography.labelLarge, color = ink)
    }
}
