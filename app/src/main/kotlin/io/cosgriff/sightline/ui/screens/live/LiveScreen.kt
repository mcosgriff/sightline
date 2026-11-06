package io.cosgriff.sightline.ui.screens.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.cosgriff.sightline.R
import io.cosgriff.sightline.analysis.Health
import io.cosgriff.sightline.analysis.HealthReason
import io.cosgriff.sightline.analysis.SignalSample
import io.cosgriff.sightline.analysis.Thresholds
import io.cosgriff.sightline.analysis.assess
import io.cosgriff.sightline.data.device.Channel
import io.cosgriff.sightline.data.live.LiveState
import io.cosgriff.sightline.protocol.Band
import io.cosgriff.sightline.protocol.Lock
import io.cosgriff.sightline.protocol.LostReason
import io.cosgriff.sightline.protocol.Modulation
import io.cosgriff.sightline.protocol.Occupancy
import io.cosgriff.sightline.protocol.RfChannels
import io.cosgriff.sightline.protocol.TunerStatus
import io.cosgriff.sightline.ui.components.GaugeValues
import io.cosgriff.sightline.ui.components.KeepScreenOn
import io.cosgriff.sightline.ui.components.LocalNetworkCard
import io.cosgriff.sightline.ui.components.PacketErrorsRow
import io.cosgriff.sightline.ui.components.SparkPoint
import io.cosgriff.sightline.ui.components.Sparkline
import io.cosgriff.sightline.ui.components.TriGauge
import io.cosgriff.sightline.ui.components.color
import io.cosgriff.sightline.ui.components.icon
import io.cosgriff.sightline.ui.components.label
import io.cosgriff.sightline.ui.components.rememberLocalNetworkAccess
import io.cosgriff.sightline.ui.theme.Numbers
import io.cosgriff.sightline.ui.theme.sightlineColors
import kotlin.math.abs

/** What Live's buttons and links open. */
data class LiveActions(
    val onAim: () -> Unit,
    val onSweep: () -> Unit,
    val onMonitor: () -> Unit,
    val onSettings: () -> Unit,
    val onDevices: () -> Unit,
    val onTuners: () -> Unit = {},
)

/** What Live's channel and tuner controls do. */
data class LiveControls(
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val onSelect: (guideNumber: String) -> Unit,
    val onRetry: () -> Unit,
    val onReload: () -> Unit,
    val onDisconnect: () -> Unit,
    val onConnect: () -> Unit,
)

@Composable
fun LiveScreen(actions: LiveActions, viewModel: LiveViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val network = rememberLocalNetworkAccess()
    if (network.granted) {
        LifecycleStartEffect(viewModel) {
            viewModel.start()
            onStopOrDispose { viewModel.stop() }
        }
    }
    if (settings.keepScreenOn && state.live != null) KeepScreenOn()
    LiveContent(
        state = state,
        networkCard = if (network.granted) null else ({ LocalNetworkCard(network) }),
        controls = LiveControls(
            onPrevious = viewModel::previous,
            onNext = viewModel::next,
            onSelect = viewModel::select,
            onRetry = viewModel::retry,
            onReload = viewModel::reload,
            onDisconnect = viewModel::disconnect,
            onConnect = viewModel::connect,
        ),
        actions = actions,
    )
}

@Composable
fun LiveContent(
    state: LiveUiState,
    networkCard: (@Composable () -> Unit)?,
    controls: LiveControls,
    actions: LiveActions,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.sightlineColors
    val setup = state.setup
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        val subtitle = when (setup) {
            is LiveSetup.Ready -> when (val live = state.live?.takeUnless { state.disconnected }) {
                is LiveState.Watching -> stringResource(R.string.live_device_tuner, setup.device.friendlyName, live.tuner)
                else -> setup.device.friendlyName
            }
            is LiveSetup.NoChannels -> setup.device.friendlyName
            else -> null
        }
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.live_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            }
            if (setup is LiveSetup.Ready) {
                IconButton(onClick = actions.onTuners) {
                    Icon(Icons.Outlined.GridView, contentDescription = stringResource(R.string.tuners_open))
                }
            }
            IconButton(onClick = actions.onSettings) {
                Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.live_settings))
            }
        }
        if (networkCard != null) {
            Column(Modifier.padding(16.dp)) { networkCard() }
            return@Column
        }
        when (setup) {
            LiveSetup.Loading -> Unit
            LiveSetup.NoDevice -> Message(R.string.live_no_device, R.string.live_open_devices, actions.onDevices)
            is LiveSetup.NoChannels -> Message(R.string.live_no_channels, R.string.live_reload, controls.onReload)
            is LiveSetup.Ready -> Ready(state, setup, controls, actions)
        }
    }
}

@Composable
private fun Message(text: Int, button: Int, onClick: () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.sightlineColors.textSecondary)
        Button(onClick = onClick) { Text(stringResource(button)) }
    }
}

@Composable
private fun Ready(
    state: LiveUiState,
    setup: LiveSetup.Ready,
    controls: LiveControls,
    actions: LiveActions,
) {
    val live = state.live?.takeUnless { state.disconnected }
    val onPrevious = controls.onPrevious
    val onNext = controls.onNext
    val watching = live as? LiveState.Watching
    val status = watching?.status
    val locked = status?.isLocked == true
    // The badge shows from the first ATSC 3.0 lock, before the stored channel learns it.
    val atsc3 = setup.channel.atsc3 || (status?.lock as? Lock.Locked)?.modulation == Modulation.ATSC3
    var picking by rememberSaveable { mutableStateOf(false) }
    ChannelPill(setup.channel, atsc3, onPrevious, onNext, onPick = { picking = true })
    if (picking) {
        ChannelPicker(
            channels = setup.channels,
            current = setup.channel,
            onPick = {
                picking = false
                controls.onSelect(it.guideNumber)
            },
            onDismiss = { picking = false },
        )
    }

    // Swipe the gauges sideways to step through channels (SPEC Live).
    Column(
        Modifier.fillMaxWidth().pointerInput(setup.channel.guideNumber) {
            var dragged = 0f
            detectHorizontalDragGestures(
                onDragStart = { dragged = 0f },
                onDragEnd = { if (abs(dragged) > 120f) if (dragged < 0) onNext() else onPrevious() },
            ) { _, amount -> dragged += amount }
        },
    ) {
        TriGauge(
            values = if (locked) GaugeValues(status.ss, status.snq, status.seq) else GaugeValues(null, null, null),
            best = watching?.trace?.best,
            thresholds = state.thresholds,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    if (watching != null) {
        TraceRow(watching, state.thresholds, state.nowMs)
        InfoRow(setup.channel, status)
        if (watching.countersRead) {
            PacketErrorsRow(
                stringResource(R.string.live_packet_errors),
                watching.errors,
                Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
                rising = watching.lastAdded.any,
            )
        }
    }
    if (state.disconnected) DisconnectedBanner(controls.onConnect)
    else StatusBanner(live, state.thresholds, controls.onRetry, controls.onDisconnect)
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = actions.onAim, modifier = Modifier.weight(1f).height(48.dp)) { Text(stringResource(R.string.live_aim)) }
        OutlinedButton(onClick = actions.onSweep, modifier = Modifier.weight(1f).height(48.dp)) { Text(stringResource(R.string.live_sweep)) }
        OutlinedButton(onClick = actions.onMonitor, modifier = Modifier.weight(1f).height(48.dp)) { Text(stringResource(R.string.live_monitor)) }
    }
}

@Composable
private fun ChannelPill(channel: Channel, atsc3: Boolean, onPrevious: () -> Unit, onNext: () -> Unit, onPick: () -> Unit) {
    val colors = MaterialTheme.sightlineColors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RoundButton(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, stringResource(R.string.live_previous_channel), onPrevious)
        Row(
            Modifier.weight(1f).height(48.dp)
                .clip(CircleShape)
                .background(colors.surface, CircleShape)
                .border(1.dp, colors.outline, CircleShape)
                .clickable(onClickLabel = stringResource(R.string.live_picker_open), role = Role.Button, onClick = onPick)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(channel.guideNumber, style = Numbers.info)
            Text(
                channel.guideName,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (atsc3) {
                Text(
                    stringResource(R.string.live_atsc3),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.accent,
                    modifier = Modifier.padding(start = 8.dp).background(colors.accent.copy(alpha = 0.12f), MaterialTheme.shapes.small).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.padding(start = 2.dp))
        }
        RoundButton(Icons.AutoMirrored.Rounded.KeyboardArrowRight, stringResource(R.string.live_next_channel), onNext)
    }
}

@Composable
private fun RoundButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    val colors = MaterialTheme.sightlineColors
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp).background(colors.surface, CircleShape).border(1.dp, colors.outline, CircleShape),
    ) { Icon(icon, contentDescription = description) }
}

@Composable
private fun TraceRow(watching: LiveState.Watching, thresholds: Thresholds, nowMs: Long) {
    val colors = MaterialTheme.sightlineColors
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            Text(stringResource(R.string.live_trace_title), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.live_trace_threshold, thresholds.snqGood), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
        val points = watching.trace.recent.map { SparkPoint(it.atMs, if (it.sample.locked) it.sample.snq else null) }
        Sparkline(
            points = points,
            nowMs = watching.trace.latest?.atMs ?: nowMs,
            color = colors.accent,
            thresholdColor = colors.outline,
            threshold = thresholds.snqGood,
            description = stringResource(R.string.live_trace_description),
            modifier = Modifier.fillMaxWidth().height(44.dp),
        )
    }
}

@Composable
private fun InfoRow(channel: Channel, status: TunerStatus?) {
    val rf = status?.let { RfChannels.of(it.channel) }
    val number = rf?.number ?: channel.rfChannel
    val band = rf?.band ?: channel.band
    val hz = rf?.centerHz ?: channel.frequencyHz
    val dash = stringResource(R.string.live_no_value)
    val lock = when (val l = status?.lock) {
        is Lock.Locked -> when (l.modulation) {
            Modulation.VSB8 -> stringResource(R.string.live_lock_8vsb)
            Modulation.ATSC3 -> stringResource(R.string.live_lock_atsc3)
            Modulation.QAM256 -> stringResource(R.string.live_lock_qam256)
            Modulation.QAM64 -> stringResource(R.string.live_lock_qam64)
            Modulation.OTHER -> l.raw.uppercase()
        }
        Lock.None -> stringResource(R.string.live_lock_none)
        null -> dash
    }
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Fact(stringResource(R.string.live_rf), number?.toString() ?: dash, mono = true, Modifier.weight(1f))
        Fact(
            stringResource(R.string.live_band),
            when (band) {
                Band.VHF_LO -> stringResource(R.string.band_vhf_lo)
                Band.VHF_HI -> stringResource(R.string.band_vhf_hi)
                Band.UHF -> stringResource(R.string.band_uhf)
                null -> dash
            },
            mono = false,
            Modifier.weight(1f),
        )
        Fact(stringResource(R.string.live_freq), hz?.let { stringResource(R.string.live_mhz, it / 1_000_000) } ?: dash, mono = true, Modifier.weight(1f))
        Fact(stringResource(R.string.live_lock), lock, mono = false, Modifier.weight(1f))
    }
}

@Composable
private fun Fact(label: String, value: String, mono: Boolean, modifier: Modifier) {
    val colors = MaterialTheme.sightlineColors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
        Text(value, style = if (mono) Numbers.info else MaterialTheme.typography.bodyMedium, maxLines = 1)
    }
}

/** The health banner while watching, or what's going on instead. */
@Composable
private fun StatusBanner(live: LiveState?, thresholds: Thresholds, onRetry: () -> Unit, onDisconnect: () -> Unit) {
    val (health, title, body) = when (live) {
        null, is LiveState.Claiming -> Triple(null, stringResource(R.string.live_claiming), null)
        is LiveState.Watching -> {
            val s = live.status
            when {
                live.missedPolls >= 3 -> Triple(Health.NO_LOCK, stringResource(R.string.live_not_answering), stringResource(R.string.live_not_answering_body))
                live.raw != null -> Triple(Health.NO_LOCK, stringResource(R.string.live_unreadable), live.raw)
                s == null -> Triple(null, stringResource(R.string.live_tuning), null)
                else -> {
                    val a = thresholds.assess(SignalSample(s.isLocked, s.ss, s.snq, s.seq))
                    Triple(a.health, stringResource(a.health.label), stringResource(reasonText(a.reason)))
                }
            }
        }
        is LiveState.AllBusy -> Triple(
            Health.NO_LOCK,
            stringResource(R.string.live_all_busy),
            live.occupancy.entries.map { (tuner, o) -> occupancyLine(tuner, o) }.joinToString("\n") +
                "\n" + stringResource(R.string.live_all_busy_retry, live.retryEvery.inWholeSeconds.toInt()),
        )
        is LiveState.Lost -> Triple(Health.POOR, stringResource(R.string.live_lost, live.tuner), lostText(live.reason))
        is LiveState.UnknownChannel -> Triple(Health.POOR, stringResource(R.string.live_unknown_channel), null)
        is LiveState.Unreachable -> Triple(Health.NO_LOCK, stringResource(R.string.live_unreachable), stringResource(R.string.live_unreachable_body))
    }
    Banner(health, title, body) {
        if (live is LiveState.Lost || live is LiveState.UnknownChannel) {
            OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.live_retry)) }
        } else {
            // Let the tuner go without leaving the screen: claiming, waiting or watching (SPEC Live).
            TextButton(onClick = onDisconnect, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.live_disconnect)) }
        }
    }
}

/** After Disconnect: nothing is held, and nothing will be until Connect. */
@Composable
private fun DisconnectedBanner(onConnect: () -> Unit) {
    Banner(null, stringResource(R.string.live_disconnected), stringResource(R.string.live_disconnected_body)) {
        OutlinedButton(onClick = onConnect) { Text(stringResource(R.string.live_connect)) }
    }
}

@Composable
private fun Banner(health: Health?, title: String, body: String?, action: @Composable () -> Unit) {
    val colors = MaterialTheme.sightlineColors
    val tint = health?.color() ?: colors.outline
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 14.dp)
            .background(tint.copy(alpha = 0.12f), MaterialTheme.shapes.medium)
            .border(1.dp, tint.copy(alpha = 0.5f), MaterialTheme.shapes.medium)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (health != null) Icon(health.icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (body != null) Text(body, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
        action()
    }
}

private fun reasonText(reason: HealthReason) = when (reason) {
    HealthReason.CLEAN -> R.string.live_reason_clean
    HealthReason.GOOD_NEAR_OVERLOAD -> R.string.live_reason_near_overload
    HealthReason.OVERLOAD -> R.string.live_reason_overload
    HealthReason.WEAK -> R.string.live_reason_weak
    HealthReason.SYMBOL_ERRORS -> R.string.live_reason_symbol_errors
    HealthReason.LOW_QUALITY -> R.string.live_reason_low_quality
    HealthReason.NO_LOCK -> R.string.live_reason_no_lock
}

@Composable
private fun occupancyLine(tuner: Int, o: Occupancy): String = when (o) {
    Occupancy.Idle -> stringResource(R.string.live_busy_idle_cant, tuner)
    is Occupancy.Busy -> o.holder?.let { stringResource(R.string.live_busy_held_by, tuner, it) }
        ?: stringResource(R.string.live_busy_held, tuner)
    Occupancy.Unknown -> stringResource(R.string.live_busy_unknown, tuner)
}

@Composable
private fun lostText(reason: LostReason): String = when (reason) {
    is LostReason.TakenBy -> reason.holder?.let { stringResource(R.string.live_lost_taken_by, it) } ?: stringResource(R.string.live_lost_taken)
    is LostReason.ChannelChanged -> stringResource(R.string.live_lost_retuned)
    LostReason.StreamEnded -> stringResource(R.string.live_lost_stream)
    LostReason.Released -> stringResource(R.string.live_lost_released)
}
