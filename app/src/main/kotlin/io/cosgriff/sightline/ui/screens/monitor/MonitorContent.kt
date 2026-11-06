package io.cosgriff.sightline.ui.screens.monitor

import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.cosgriff.sightline.R
import io.cosgriff.sightline.ui.components.PacketErrorsRow
import io.cosgriff.sightline.ui.components.Choice
import io.cosgriff.sightline.ui.components.Choices
import io.cosgriff.sightline.analysis.EventKind
import io.cosgriff.sightline.analysis.MonitorEstimate
import io.cosgriff.sightline.analysis.SignalEvent
import io.cosgriff.sightline.analysis.Thresholds
import io.cosgriff.sightline.analysis.dipCount
import io.cosgriff.sightline.analysis.longestDipMs
import io.cosgriff.sightline.data.monitor.MonitorEnd
import io.cosgriff.sightline.data.device.TunerHolder
import io.cosgriff.sightline.monitor.monitorTitle
import io.cosgriff.sightline.data.monitor.MonitorPhase
import io.cosgriff.sightline.data.monitor.MonitorStatus
import io.cosgriff.sightline.protocol.LostReason
import io.cosgriff.sightline.ui.components.SignalTimeline
import io.cosgriff.sightline.ui.theme.Numbers
import io.cosgriff.sightline.ui.theme.sightlineColors
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

data class MonitorActions(
    val onSelectDevice: (String) -> Unit,
    val onToggleChannel: (String) -> Unit,
    val onRotate: (Boolean) -> Unit,
    val onWatch: (Boolean) -> Unit = {},
    val onTuner: (Int?) -> Unit,
    val onDwell: (Int) -> Unit,
    val onDuration: (MonitorDuration) -> Unit,
    val onPoll: (Long) -> Unit,
    val onDipSnq: (Int) -> Unit,
    val onFetchWeather: (Boolean) -> Unit,
    val onNotifyDips: (Boolean) -> Unit,
    val onStart: () -> Unit,
    val onStop: () -> Unit,
    val onDismiss: () -> Unit,
    /** Opens a finished session in Session detail. */
    val onOpenSession: (Long) -> Unit = {},
)

/** The Monitor tab: the running session per `mockups/Monitor.png`, otherwise the setup (SPEC Screens 6 and 7). */
@Composable
fun MonitorContent(
    state: MonitorUiState,
    nowMs: Long,
    zone: ZoneId,
    networkCard: (@Composable () -> Unit)?,
    actions: MonitorActions,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.sightlineColors
    val status = state.status
    Column(modifier.fillMaxSize().background(colors.bg)) {
        if (status != null && status.ended == null) {
            Running(status, state.thresholds, nowMs, zone, actions.onStop)
        } else {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Title(stringResource(R.string.monitor_title), stringResource(R.string.monitor_setup_subtitle))
                if (status != null) Ended(status, actions.onDismiss, actions.onOpenSession)
                if (networkCard != null) {
                    Box(Modifier.padding(16.dp)) { networkCard() }
                } else {
                    Setup(state.setup, actions)
                }
            }
        }
    }
}

@Composable
private fun Title(title: String, subtitle: String?, live: Boolean = false) {
    val colors = MaterialTheme.sightlineColors
    Column(Modifier.padding(start = 20.dp, end = 16.dp, top = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (live) {
                Box(
                    Modifier.size(18.dp).background(colors.healthPoor.copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.size(10.dp).background(colors.healthPoor, CircleShape)) }
            }
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        }
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Setup

@Composable
private fun Setup(setup: SetupState, actions: MonitorActions) {
    val colors = MaterialTheme.sightlineColors
    when (setup) {
        SetupState.Loading -> Unit
        SetupState.NoDevice -> Note(stringResource(R.string.monitor_no_device))
        is SetupState.NoChannels -> Note(stringResource(R.string.monitor_no_channels, setup.device.friendlyName))
        is SetupState.Ready -> {
            val s = setup.setup
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (s.devices.size > 1) DevicePicker(s, actions.onSelectDevice)

                Field(stringResource(R.string.monitor_setup_mode)) {
                    Choices {
                        Choice(stringResource(R.string.monitor_setup_hold), !s.rotate && !s.watch) { actions.onRotate(false) }
                        Choice(stringResource(R.string.monitor_setup_rotate), s.rotate) { actions.onRotate(true) }
                        Choice(stringResource(R.string.monitor_setup_watch), s.watch) { actions.onWatch(true) }
                    }
                }
                if (s.watch) {
                    WatchTuner(s, actions.onTuner)
                } else Field(stringResource(if (s.rotate) R.string.monitor_setup_channels else R.string.monitor_setup_channel)) {
                    Choices {
                        s.channels.forEach { c ->
                            Choice(stringResource(R.string.monitor_setup_channel_chip, c.guideNumber, c.guideName), c.guideNumber in s.selected) {
                                actions.onToggleChannel(c.guideNumber)
                            }
                        }
                    }
                }
                if (s.rotate) {
                    Field(stringResource(R.string.monitor_setup_dwell)) {
                        Choices {
                            MONITOR_DWELL_CHOICES.forEach { sec ->
                                Choice(stringResource(R.string.monitor_seconds, sec), s.dwellS == sec) { actions.onDwell(sec) }
                            }
                        }
                    }
                }
                if (!s.watch) {
                    Field(stringResource(R.string.monitor_setup_tuner)) {
                        Choices {
                            Choice(stringResource(R.string.monitor_setup_any_tuner), s.tuner == null) { actions.onTuner(null) }
                            (0 until s.device.tunerCount).forEach { t ->
                                Choice(stringResource(R.string.monitor_setup_tuner_chip, t), s.tuner == t) { actions.onTuner(t) }
                            }
                        }
                    }
                }
                Field(stringResource(R.string.monitor_setup_duration)) {
                    Choices {
                        MonitorDuration.entries.forEach { d ->
                            Choice(durationLabel(d), s.duration == d) { actions.onDuration(d) }
                        }
                    }
                }
                Field(stringResource(R.string.monitor_setup_poll)) {
                    Choices {
                        MONITOR_POLL_CHOICES.forEach { ms ->
                            val label = if (ms == 1_000L) stringResource(R.string.monitor_setup_poll_1s)
                            else stringResource(R.string.monitor_setup_poll_saver, (ms / 1_000).toInt())
                            Choice(label, s.pollMs == ms) { actions.onPoll(ms) }
                        }
                    }
                }
                DipThreshold(s.dipSnq, actions.onDipSnq)
                Toggle(stringResource(R.string.monitor_setup_notify), stringResource(R.string.monitor_setup_notify_note), s.notifyDips, actions.onNotifyDips)
                Toggle(stringResource(R.string.monitor_setup_weather), stringResource(R.string.monitor_setup_weather_note), s.fetchWeather, actions.onFetchWeather)

                Estimate(s)
                Text(stringResource(R.string.monitor_setup_charger), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                Button(onClick = actions.onStart, enabled = s.canStart, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text(stringResource(R.string.monitor_start))
                }
            }
        }
    }
}

/** Watch mode's tuner pick: each tuner with who is using it, read when the mode was picked. */
@Composable
private fun WatchTuner(s: MonitorSetup, onTuner: (Int?) -> Unit) {
    val colors = MaterialTheme.sightlineColors
    Field(stringResource(R.string.monitor_setup_watch_tuner)) {
        Choices {
            (0 until s.device.tunerCount).forEach { t ->
                val label = when (val h = s.holders?.getOrNull(t)) {
                    is TunerHolder.Other -> h.address?.let { stringResource(R.string.monitor_setup_watch_chip_by, t, it) }
                        ?: stringResource(R.string.monitor_setup_watch_chip_busy, t)
                    TunerHolder.Idle -> stringResource(R.string.monitor_setup_watch_chip_idle, t)
                    TunerHolder.ThisApp, is TunerHolder.ThisPhone -> stringResource(R.string.monitor_setup_watch_chip_this, t)
                    else -> stringResource(R.string.monitor_setup_tuner_chip, t)
                }
                Choice(label, s.tuner == t) { onTuner(t) }
            }
        }
        Text(stringResource(R.string.monitor_setup_watch_note), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.sightlineColors.textSecondary,
        modifier = Modifier.padding(20.dp),
    )
}

@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

@Composable
private fun DevicePicker(s: MonitorSetup, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Field(stringResource(R.string.monitor_setup_device)) {
        Box {
            OutlinedButton(onClick = { open = true }, shape = MaterialTheme.shapes.small) { Text(s.device.friendlyName) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                s.devices.forEach { d ->
                    DropdownMenuItem(text = { Text(d.friendlyName) }, onClick = {
                        open = false
                        onSelect(d.deviceId)
                    })
                }
            }
        }
    }
}

@Composable
private fun DipThreshold(snq: Int, onChange: (Int) -> Unit) {
    val colors = MaterialTheme.sightlineColors
    Field(stringResource(R.string.monitor_setup_dip)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onChange(snq - 5) }) {
                Icon(Icons.Rounded.Remove, contentDescription = stringResource(R.string.monitor_setup_dip_lower))
            }
            Text(snq.toString(), style = Numbers.stat, modifier = Modifier.padding(horizontal = 8.dp))
            IconButton(onClick = { onChange(snq + 5) }) {
                Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.monitor_setup_dip_raise))
            }
        }
        Text(stringResource(R.string.monitor_setup_dip_note), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
    }
}

@Composable
private fun Toggle(label: String, note: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.sightlineColors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(note, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
}

@Composable
private fun Estimate(s: MonitorSetup) {
    val context = LocalContext.current
    val numbers = NumberFormat.getIntegerInstance()
    val hours = s.duration.hours
    val e = MonitorEstimate.of((hours ?: 1) * 3_600_000L, s.pollMs)
    val size = Formatter.formatShortFileSize(context, e.bytes)
    val text = if (hours != null) stringResource(R.string.monitor_estimate, numbers.format(e.readings), size)
    else stringResource(R.string.monitor_estimate_hourly, numbers.format(e.readings), size)
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun durationLabel(d: MonitorDuration): String =
    d.hours?.let { stringResource(R.string.monitor_hours, it) } ?: stringResource(R.string.monitor_until_stopped)

@Composable
private fun Ended(status: MonitorStatus, onDismiss: () -> Unit, onOpen: (Long) -> Unit) {
    val colors = MaterialTheme.sightlineColors
    val ran = elapsedText(((status.endedAt ?: status.startedAt) - status.startedAt).coerceAtLeast(0))
    val dips = status.events.dipCount()
    val text = when (status.ended) {
        MonitorEnd.NO_CHANNEL -> stringResource(R.string.monitor_ended_refused)
        MonitorEnd.TIME_UP -> pluralStringResource(R.plurals.monitor_ended_time_up, dips, dips, ran)
        else -> pluralStringResource(R.plurals.monitor_ended_stopped, dips, dips, ran)
    }
    Column(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth()
            .background(colors.surface, MaterialTheme.shapes.medium).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.monitor_ended_title), style = MaterialTheme.typography.titleMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.monitor_ended_where), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Refused every channel: nothing was read, so there is no session worth opening.
            if (status.ended != MonitorEnd.NO_CHANNEL) {
                TextButton(onClick = { onOpen(status.sessionId) }) { Text(stringResource(R.string.monitor_ended_open)) }
            }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.monitor_ended_done)) }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Running

@Composable
private fun ColumnScope.Running(status: MonitorStatus, thresholds: Thresholds, nowMs: Long, zone: ZoneId, onStop: () -> Unit) {
    val colors = MaterialTheme.sightlineColors
    val plan = status.plan
    var confirmStop by remember { mutableStateOf(false) }
    val title = monitorTitle(LocalResources.current, status)
    val rate = if (plan.pollIntervalMs <= 1_000) stringResource(R.string.monitor_rate_1s)
    else stringResource(R.string.monitor_rate_every, (plan.pollIntervalMs / 1_000).toInt())
    val tuner = when (val p = status.phase) {
        is MonitorPhase.Watching -> p.tuner
        is MonitorPhase.Observing -> p.tuner
        is MonitorPhase.ObservingIdle -> p.tuner
        else -> null
    }
    val subtitle = if (tuner != null) stringResource(R.string.monitor_subtitle_tuner, plan.device.friendlyName, tuner, rate)
    else stringResource(R.string.monitor_subtitle, plan.device.friendlyName, rate)
    val events = status.allEvents

    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Title(title, subtitle, live = true)
        PhaseLine(status)
        Progress(status, nowMs, zone)
        Stats(events, status)
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp).fillMaxWidth()
                .background(colors.surface, MaterialTheme.shapes.medium).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.monitor_chart_title), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, modifier = Modifier.weight(1f))
                Legend(colors.healthPoor.copy(alpha = 0.35f), stringResource(R.string.monitor_chart_dip))
                if (events.any { !it.isDip }) {
                    Spacer(Modifier.size(10.dp))
                    Legend(colors.healthNoLock.copy(alpha = 0.35f), stringResource(R.string.monitor_chart_no_data))
                }
            }
            val end = status.endsAt
            SignalTimeline(
                lines = listOf(status.timeline),
                events = events,
                nowMs = nowMs,
                endMs = end,
                threshold = thresholds.snqGood,
                timeLabels = timeLabels(status.startedAt, end ?: nowMs, zone),
                stillToCome = stringResource(R.string.monitor_chart_still_to_come),
                description = pluralStringResource(R.plurals.monitor_chart_description, events.dipCount(), events.dipCount()),
            )
        }
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.monitor_recent), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            val latest = status.latest
            Text(
                when {
                    latest == null -> ""
                    latest.isLocked -> stringResource(R.string.monitor_now, latest.snq, latest.ss, latest.seq)
                    else -> stringResource(R.string.monitor_now_no_lock)
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
        val recent = events.sortedByDescending { it.startMs }.take(RECENT_COUNT)
        if (recent.isEmpty()) {
            Text(
                stringResource(R.string.monitor_no_dips),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            recent.forEach { e ->
                EventRow(e, thresholds, zone)
                HorizontalDivider(color = colors.outline)
            }
        }
    }
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.monitor_footer),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = { confirmStop = true },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.healthPoor),
            border = BorderStroke(1.dp, colors.healthPoor),
        ) { Text(stringResource(R.string.monitor_stop_button), fontWeight = FontWeight.SemiBold) }
    }
    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(stringResource(R.string.monitor_stop_confirm_title)) },
            text = { Text(stringResource(R.string.monitor_stop_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    onStop()
                }) { Text(stringResource(R.string.monitor_stop)) }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(stringResource(R.string.monitor_stop_keep)) } },
        )
    }
}

/** What the monitor is doing when it isn't simply reading, said in one line. */
@Composable
private fun PhaseLine(status: MonitorStatus) {
    val colors = MaterialTheme.sightlineColors
    val text = when (val p = status.phase) {
        MonitorPhase.Claiming -> stringResource(R.string.monitor_phase_claiming)
        is MonitorPhase.AllBusy -> stringResource(R.string.monitor_phase_all_busy, p.retryEvery.inWholeSeconds.toInt())
        is MonitorPhase.Paused -> (p.reason as? LostReason.TakenBy)?.holder
            ?.let { stringResource(R.string.monitor_phase_paused_by, it, p.retryEvery.inWholeSeconds.toInt()) }
            ?: stringResource(R.string.monitor_phase_paused, p.retryEvery.inWholeSeconds.toInt())
        is MonitorPhase.Unreachable -> stringResource(R.string.monitor_phase_unreachable, p.retryIn.inWholeSeconds.toInt().coerceAtLeast(1))
        is MonitorPhase.Watching -> when {
            p.missedPolls > 0 -> pluralStringResource(R.plurals.monitor_phase_missed, p.missedPolls, p.missedPolls)
            status.plan.rotate -> stringResource(R.string.monitor_phase_rotating, status.channel.guideNumber, (status.plan.dwellMs / 1_000).toInt())
            else -> null
        }
        is MonitorPhase.Observing -> when {
            p.missedPolls > 0 -> pluralStringResource(R.plurals.monitor_phase_missed, p.missedPolls, p.missedPolls)
            else -> p.holder?.let { stringResource(R.string.monitor_phase_observing_by, it) }
                ?: stringResource(R.string.monitor_phase_observing)
        }
        is MonitorPhase.ObservingIdle -> stringResource(R.string.monitor_phase_observing_idle, p.tuner)
    } ?: return
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if ((status.phase as? MonitorPhase.Watching)?.missedPolls?.let { it > 0 } == true ||
            (status.phase as? MonitorPhase.Observing)?.missedPolls?.let { it > 0 } == true
        ) colors.healthMarginal else colors.textSecondary,
        modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun Progress(status: MonitorStatus, nowMs: Long, zone: ZoneId) {
    val colors = MaterialTheme.sightlineColors
    val elapsed = (nowMs - status.startedAt).coerceAtLeast(0)
    val duration = status.plan.durationMs
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(elapsedText(elapsed), style = Numbers.elapsed, modifier = Modifier.weight(1f))
            Text(
                if (duration != null) {
                    stringResource(R.string.monitor_of_ends, stringResource(R.string.monitor_hours, (duration / 3_600_000).toInt()), clockTime(status.startedAt + duration, zone))
                } else {
                    stringResource(R.string.monitor_until_stopped_lower)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
            )
        }
        if (duration != null) {
            LinearProgressIndicator(
                progress = { (elapsed.toFloat() / duration).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = colors.accent,
                trackColor = colors.outline,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}

@Composable
private fun Stats(events: List<SignalEvent>, status: MonitorStatus) {
    val colors = MaterialTheme.sightlineColors
    val dips = events.dipCount()
    val longest = events.longestDipMs()
    val good = if (status.readings > 0) status.goodReadings * 100.0 / status.readings else null
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Stat(stringResource(R.string.monitor_stat_dips), dips.toString(), if (dips > 0) colors.healthPoor else colors.healthGood, Modifier.weight(1f))
        Stat(stringResource(R.string.monitor_stat_longest), if (dips > 0) shortDuration(longest) else stringResource(R.string.monitor_none), colors.textPrimary, Modifier.weight(1f))
        Stat(
            stringResource(R.string.monitor_stat_good),
            good?.let { stringResource(R.string.monitor_percent, it) } ?: stringResource(R.string.monitor_none),
            when {
                good == null -> colors.textPrimary
                good >= 99 -> colors.healthGood
                good >= 95 -> colors.healthMarginal
                else -> colors.healthPoor
            },
            Modifier.weight(1f),
        )
    }
    // The tuner's own counters catch errors between polls; shown once it has given them (SPEC "Packet errors").
    if (status.countersRead) {
        PacketErrorsRow(stringResource(R.string.monitor_packet_errors), status.errors, Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp))
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color, modifier: Modifier) {
    val colors = MaterialTheme.sightlineColors
    Column(
        modifier.background(colors.surface, MaterialTheme.shapes.medium).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        Text(value, style = Numbers.stat, color = color)
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).background(color, MaterialTheme.shapes.extraSmall))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.sightlineColors.textSecondary)
    }
}

@Composable
private fun EventRow(e: SignalEvent, thresholds: Thresholds, zone: ZoneId) {
    val colors = MaterialTheme.sightlineColors
    val (label, color) = when (e.kind) {
        EventKind.DIP -> R.string.monitor_event_dip to
            if ((e.minSnq ?: 0) >= thresholds.snqMarginal) colors.healthMarginal else colors.healthPoor
        EventKind.DROPOUT -> R.string.monitor_event_dropout to colors.healthPoor
        EventKind.TUNER_LOST -> R.string.monitor_event_tuner_lost to colors.healthMarginal
        EventKind.DEVICE_UNREACHABLE -> R.string.monitor_event_unreachable to colors.healthNoLock
        EventKind.GAP -> R.string.monitor_event_gap to colors.healthNoLock
    }
    val detail = when (e.kind) {
        EventKind.DIP -> stringResource(R.string.monitor_event_dip_detail, e.minSnq ?: 0, e.minSeq ?: 0)
        EventKind.DROPOUT -> e.minSnq?.let { stringResource(R.string.monitor_event_dropout_detail, it, shortDuration(e.noLockMs)) }
            ?: stringResource(R.string.monitor_event_dropout_only, shortDuration(e.noLockMs))
        EventKind.TUNER_LOST -> stringResource(R.string.monitor_event_tuner_lost_detail)
        EventKind.DEVICE_UNREACHABLE -> stringResource(R.string.monitor_event_unreachable_detail)
        EventKind.GAP -> stringResource(R.string.monitor_event_gap_detail)
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(stringResource(R.string.monitor_event_when, clockTime(e.startMs, zone), shortDuration(e.durationMs)), style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        }
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.SemiBold)
    }
}

/** "6h 12m", or "12m 05s" in the first hour. */
@Composable
private fun elapsedText(ms: Long): String {
    val s = ms / 1_000
    return if (s >= 3_600) stringResource(R.string.monitor_elapsed_hm, (s / 3_600).toInt(), ((s % 3_600) / 60).toInt())
    else stringResource(R.string.monitor_elapsed_ms, (s / 60).toInt(), (s % 60).toInt())
}

/** "38 s", "4 min", "2 h 5 min". */
@Composable
private fun shortDuration(ms: Long): String {
    val s = (ms + 500) / 1_000
    return when {
        s < 60 -> stringResource(R.string.monitor_seconds, s.toInt())
        s < 3_600 -> stringResource(R.string.monitor_minutes, (s / 60).toInt())
        else -> stringResource(R.string.monitor_hours_minutes, (s / 3_600).toInt(), ((s % 3_600) / 60).toInt())
    }
}

private fun clockTime(ms: Long, zone: ZoneId): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()).format(Instant.ofEpochMilli(ms).atZone(zone))

/** Five times spread from [fromMs] to [toMs], for under the timeline. */
private fun timeLabels(fromMs: Long, toMs: Long, zone: ZoneId): List<String> =
    (0..4).map { i -> clockTime(fromMs + (toMs - fromMs) * i / 4, zone) }

private const val RECENT_COUNT = 3
