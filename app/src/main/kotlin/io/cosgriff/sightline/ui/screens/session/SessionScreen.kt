package io.cosgriff.sightline.ui.screens.session

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.cosgriff.sightline.R
import io.cosgriff.sightline.analysis.EventKind
import io.cosgriff.sightline.analysis.SignalEvent
import io.cosgriff.sightline.analysis.Strength
import io.cosgriff.sightline.analysis.SweepSummary
import io.cosgriff.sightline.analysis.SweptChannel
import io.cosgriff.sightline.analysis.Thresholds
import io.cosgriff.sightline.analysis.WeatherHour
import io.cosgriff.sightline.analysis.WindRule
import io.cosgriff.sightline.data.db.SessionKind
import io.cosgriff.sightline.data.device.Channel
import io.cosgriff.sightline.data.session.SessionDetail
import io.cosgriff.sightline.data.session.SessionWeather
import io.cosgriff.sightline.data.settings.Units
import io.cosgriff.sightline.ui.components.ChannelRangeBars
import io.cosgriff.sightline.ui.components.RangeRow
import io.cosgriff.sightline.ui.components.SignalTimeline
import io.cosgriff.sightline.ui.components.bandLabel
import io.cosgriff.sightline.ui.components.clockTime
import io.cosgriff.sightline.ui.components.compactDuration
import io.cosgriff.sightline.ui.components.dateHour
import io.cosgriff.sightline.ui.components.dateTime
import io.cosgriff.sightline.ui.components.rememberHomeFix
import io.cosgriff.sightline.ui.components.sessionLength
import io.cosgriff.sightline.ui.components.speedText
import io.cosgriff.sightline.ui.theme.Numbers
import io.cosgriff.sightline.ui.theme.sightlineColors
import kotlinx.coroutines.launch
import java.time.ZoneId

data class SessionActions(
    val onBack: () -> Unit,
    val onZoom: (startMs: Long, endMs: Long) -> Unit,
    val onZoomOut: () -> Unit,
    val onWeatherMetric: (WeatherMetric) -> Unit,
    val onRetryWeather: () -> Unit,
    val onUseLocation: () -> Unit,
)

@Composable
fun SessionScreen(sessionId: Long, onBack: () -> Unit) {
    val viewModel = hiltViewModel<SessionViewModel, SessionViewModel.Factory>(key = "session-$sessionId") { it.create(sessionId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val useLocation = rememberHomeFix(viewModel::saveHome)
    SessionContent(
        state = state,
        thresholds = settings.thresholds,
        units = settings.units,
        zone = ZoneId.systemDefault(),
        actions = SessionActions(onBack, viewModel::zoom, viewModel::zoomOut, viewModel::showWeather, viewModel::retryWeather, useLocation),
    )
}

/** Session detail (SPEC Screens › 8, `mockups/Session.png`), top to bottom as the spec lists it. */
@Composable
fun SessionContent(
    state: SessionUiState,
    thresholds: Thresholds,
    units: Units,
    zone: ZoneId,
    actions: SessionActions,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.sightlineColors
    val detail = state.detail
    Column(modifier.fillMaxSize().background(colors.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
        when {
            state.loading -> {
                Header(stringResource(R.string.session_title_monitor), null, actions.onBack)
                Message(stringResource(R.string.session_loading), progress = true)
            }
            detail == null -> {
                Header(stringResource(R.string.session_title_monitor), null, actions.onBack)
                Message(stringResource(R.string.session_gone))
            }
            else -> Loaded(state, detail, thresholds, units, zone, actions)
        }
    }
}

@Composable
private fun Loaded(state: SessionUiState, detail: SessionDetail, thresholds: Thresholds, units: Units, zone: ZoneId, actions: SessionActions) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val data = detail.data
    val zoomTo: (Long, Long) -> Unit = { start, end ->
        actions.onZoom(start, end)
        scope.launch { list.animateScrollToItem(TIMELINE_ITEM) }
    }
    Header(title(detail), subtitle(detail, zone), actions.onBack)
    if (data.readings.isEmpty()) {
        Message(stringResource(R.string.session_no_readings))
        return
    }
    LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
        item(key = "cards") { SummaryCards(detail) }
        item(key = "diagnosis") { Diagnosis(detail, units, zone) }
        item(key = "timeline") { Timeline(state, detail, thresholds, units, zone, actions) }
        if (data.endMs - data.startMs > HEATMAP_AFTER_MS) {
            item(key = "heatmap") {
                Section(stringResource(R.string.session_heatmap_title), stringResource(R.string.session_heatmap_subtitle, thresholds.snqGood)) {
                    HourHeatmap(
                        rows = data.guideNumbers.map { it to detail.stats.hours.getValue(it) },
                        zone = zone,
                        onHour = { start -> zoomTo(start, start + WeatherHour.HOUR_MS) },
                    )
                }
            }
        }
        if (detail.stats.channels.size > 1) {
            item(key = "ranges") {
                Section(stringResource(R.string.session_ranges_title)) {
                    ChannelRangeBars(SweepSummary(detail.stats.channels).worstFirst.map { rangeRow(it, detail.channels[it.guideNumber]) }, thresholds.snqGood)
                }
            }
        }
        item(key = "histogram") {
            Section(stringResource(R.string.session_histogram_title)) { Histogram(detail.stats.histogram, thresholds) }
        }
        item(key = "events") { Events(detail, units, zone, zoomTo) }
        item(key = "end") { Spacer(Modifier.height(24.dp)) }
    }
}

/** The timeline's position in the list, for scrolling to it when an event or hour is tapped. */
private const val TIMELINE_ITEM = 2

/** The heatmap shows for sessions longer than this (SPEC: "for sessions longer than 12 h"). */
private const val HEATMAP_AFTER_MS = 12 * 3_600_000L

private const val EVENTS_SHOWN = 3

@Composable
private fun title(detail: SessionDetail): String = detail.info.note?.takeIf { it.isNotBlank() } ?: stringResource(
    when (detail.info.kind) {
        SessionKind.MONITOR -> R.string.session_title_monitor
        SessionKind.SWEEP -> R.string.session_title_sweep
        SessionKind.LIVE -> R.string.session_title_live
        SessionKind.AIM -> R.string.session_title_aim
    },
)

/** "Nov 2, 6 PM – Nov 4, 6 AM · 36 h · 9.1 and 4.1". */
@Composable
private fun subtitle(detail: SessionDetail, zone: ZoneId): String {
    val d = detail.data
    val channels = d.guideNumbers.ifEmpty { detail.info.channels }
    val names = when (channels.size) {
        1 -> channels.single()
        2 -> stringResource(R.string.session_and, channels[0], channels[1])
        else -> pluralStringResource(R.plurals.session_channels, channels.size, channels.size)
    }
    return stringResource(R.string.session_subtitle, dateHour(d.startMs, zone), dateHour(d.endMs, zone), sessionLength(d.endMs - d.startMs), names)
}

@Composable
private fun Header(title: String, subtitle: String?, onBack: () -> Unit) {
    val colors = MaterialTheme.sightlineColors
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.session_back))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
    }
}

@Composable
private fun Message(text: String, progress: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (progress) CircularProgressIndicator(color = MaterialTheme.sightlineColors.accent)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.sightlineColors.textSecondary)
    }
}

@Composable
private fun Section(title: String, subtitle: String? = null, card: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.sightlineColors
    Column(Modifier.fillMaxWidth().padding(top = 22.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = if (subtitle == null) 8.dp else 2.dp).semantics { heading() },
        )
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp))
        }
        val inner = Modifier.padding(horizontal = 16.dp).fillMaxWidth()
        Column(
            if (card) inner.background(colors.surface, MaterialTheme.shapes.medium).padding(12.dp) else inner,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
private fun SummaryCards(detail: SessionDetail) {
    val colors = MaterialTheme.sightlineColors
    val s = detail.stats
    val none = stringResource(R.string.session_dash)
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Card(stringResource(R.string.session_card_good), s.goodShare?.let { stringResource(R.string.session_percent, it * 100) } ?: none, colors.healthGood, Modifier.weight(1f))
            Card(stringResource(R.string.session_card_dips), s.dips.toString(), if (s.dips > 0) colors.healthPoor else colors.textPrimary, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Card(
                stringResource(R.string.session_card_dropout),
                s.longestDropoutMs?.let { compactDuration(it) } ?: stringResource(R.string.session_none),
                colors.textPrimary,
                Modifier.weight(1f),
            )
            Card(stringResource(R.string.session_card_median), s.medianSnq?.toString() ?: none, colors.textPrimary, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Card(label: String, value: String, tint: Color, modifier: Modifier) {
    val colors = MaterialTheme.sightlineColors
    Column(
        modifier.background(colors.surface, MaterialTheme.shapes.medium).padding(12.dp).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        Text(value, style = Numbers.stat, color = tint)
    }
}

@Composable
private fun Diagnosis(detail: SessionDetail, units: Units, zone: ZoneId) {
    Section(stringResource(R.string.session_diagnosis), card = false) {
        if (detail.findings.isEmpty()) {
            val s = detail.stats
            val dash = stringResource(R.string.session_dash)
            FindingCard(
                FindingText(
                    stringResource(R.string.session_no_pattern),
                    stringResource(
                        R.string.session_no_pattern_evidence,
                        s.goodShare?.let { stringResource(R.string.session_percent, it * 100) } ?: dash,
                        pluralStringResource(R.plurals.session_dips_count, s.dips, s.dips),
                        s.medianSnq?.toString() ?: dash,
                    ),
                    stringResource(R.string.session_no_pattern_fix),
                ),
                strength = null,
            )
        } else {
            for (f in detail.findings) FindingCard(f.text(units, zone), f.strength)
        }
    }
}

@Composable
private fun FindingCard(text: FindingText, strength: Strength?) {
    val colors = MaterialTheme.sightlineColors
    Column(
        Modifier.fillMaxWidth()
            .background(colors.surface, MaterialTheme.shapes.medium)
            .border(1.dp, colors.outline, MaterialTheme.shapes.medium)
            .padding(14.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (strength != null) Badge(strength)
        }
        Text(text.evidence, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        Text(text.fix, style = MaterialTheme.typography.bodyMedium, color = colors.accent)
    }
}

/** Likely in the marginal colour, Possible in neutral, both with the word. */
@Composable
private fun Badge(strength: Strength) {
    val colors = MaterialTheme.sightlineColors
    val (bg, fg, label) = when (strength) {
        Strength.LIKELY -> Triple(colors.healthMarginal, colors.bg, R.string.session_likely)
        Strength.POSSIBLE -> Triple(colors.outline, colors.textPrimary, R.string.session_possible)
    }
    Text(
        stringResource(label),
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        color = fg,
        modifier = Modifier.background(bg, MaterialTheme.shapes.small).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun Timeline(state: SessionUiState, detail: SessionDetail, thresholds: Thresholds, units: Units, zone: ZoneId, actions: SessionActions) {
    val colors = MaterialTheme.sightlineColors
    val window = state.window ?: return
    val weather = detail.weather
    val hours = (weather as? SessionWeather.Fetched)?.hours.orEmpty().filter { it.hourMs + WeatherHour.HOUR_MS > window.startMs && it.hourMs < window.endMs }
    val windy = hours.any { it.gustKph != null }
    val title = stringResource(if (windy) R.string.session_timeline_title_wind else R.string.session_timeline_title)
    Section(title) {
        Legend(detail.data.guideNumbers, if (hours.isNotEmpty()) state.weatherMetric else null)
        val span = window.endMs - window.startMs
        val labels = (0..3).map { i ->
            val at = window.startMs + span * i / 3
            if (span > 20 * WeatherHour.HOUR_MS) dateHour(at, zone) else clockTime(at, zone)
        }
        val events = detail.data.events.filter { it.endMs >= window.startMs && it.startMs <= window.endMs }
        val dips = events.count { it.isDip }
        SignalTimeline(
            lines = state.lines,
            events = events,
            nowMs = window.endMs,
            endMs = window.endMs,
            threshold = thresholds.snqGood,
            timeLabels = if (hours.isEmpty()) labels else emptyList(),
            stillToCome = null,
            description = stringResource(
                R.string.session_timeline_description,
                dateTime(window.startMs, zone),
                dateTime(window.endMs, zone),
                pluralStringResource(R.plurals.session_dips_count, dips, dips),
            ),
            chartHeight = 140.dp,
            markLatest = false,
        )
        if (hours.isNotEmpty()) {
            WeatherPanel(hours, state.weatherMetric, window.startMs, window.endMs, units, WindRule.GUSTY_KPH)
            io.cosgriff.sightline.ui.components.TimeLabels(labels)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in WeatherMetric.entries) {
                    MetricChip(
                        stringResource(
                            when (m) {
                                WeatherMetric.GUSTS -> R.string.session_weather_gusts
                                WeatherMetric.TEMPERATURE -> R.string.session_weather_temperature
                                WeatherMetric.RAIN -> R.string.session_weather_rain
                            },
                        ),
                        m == state.weatherMetric,
                    ) { actions.onWeatherMetric(m) }
                }
            }
        }
        if (state.zoomed) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.session_zoomed, dateTime(window.startMs, zone), clockTime(window.endMs, zone)),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = actions.onZoomOut) { Text(stringResource(R.string.session_zoom_out)) }
            }
        }
        WeatherNote(weather, state.fetchingWeather, actions)
    }
}

/** What to say when weather was asked for and isn't real or isn't there. */
@Composable
private fun WeatherNote(weather: SessionWeather, fetching: Boolean, actions: SessionActions) {
    val colors = MaterialTheme.sightlineColors
    val note: @Composable (String) -> Unit = { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary) }
    when (weather) {
        SessionWeather.NotAsked -> Unit
        is SessionWeather.Fetched -> if (weather.demo) note(stringResource(R.string.session_weather_demo))
        SessionWeather.Missing -> {
            note(stringResource(R.string.session_weather_missing))
            TextButton(onClick = actions.onRetryWeather, enabled = !fetching) { Text(stringResource(R.string.session_weather_retry)) }
        }
        SessionWeather.NoHome -> {
            note(stringResource(R.string.session_weather_no_home))
            TextButton(onClick = actions.onUseLocation, enabled = !fetching) { Text(stringResource(R.string.session_weather_use_location)) }
        }
    }
}

/** Line swatches named by channel, and the weather bars. Colour and name together, so neither is alone. */
@Composable
private fun Legend(guides: List<String>, weather: WeatherMetric?) {
    val colors = MaterialTheme.sightlineColors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        guides.forEachIndexed { i, g ->
            val c = if (i == 0) colors.accent else colors.chartLines[(i - 1) % colors.chartLines.size]
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.width(14.dp).height(2.dp).background(c))
                Text(stringResource(R.string.session_timeline_line, g), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
            }
        }
        if (weather != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(10.dp).background(colors.weatherBar, MaterialTheme.shapes.extraSmall))
                Text(
                    stringResource(
                        when (weather) {
                            WeatherMetric.GUSTS -> R.string.session_weather_gusts
                            WeatherMetric.TEMPERATURE -> R.string.session_weather_temperature
                            WeatherMetric.RAIN -> R.string.session_weather_rain
                        },
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun MetricChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.sightlineColors
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        shape = MaterialTheme.shapes.small,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = colors.surface,
            labelColor = colors.textPrimary,
            selectedContainerColor = colors.accent.copy(alpha = 0.18f),
            selectedLabelColor = colors.accent,
        ),
        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selected, borderColor = colors.outline, selectedBorderColor = colors.accent),
    )
}

@Composable
private fun rangeRow(c: SweptChannel, channel: Channel?): RangeRow {
    val rf = channel?.rfChannel
    val band = channel?.band?.let { bandLabel(it) }
    val subtitle = when {
        rf != null && band != null -> stringResource(R.string.session_rf_band, rf, band)
        rf != null -> stringResource(R.string.session_rf, rf)
        else -> channel?.guideName ?: ""
    }
    return RangeRow(c.guideNumber, c.guideNumber, subtitle, c.minSnq, c.medianSnq, c.maxSnq, c.health)
}

@Composable
private fun Events(detail: SessionDetail, units: Units, zone: ZoneId, onZoom: (Long, Long) -> Unit) {
    val colors = MaterialTheme.sightlineColors
    var all by rememberSaveable { mutableStateOf(false) }
    val events = detail.data.events.sortedByDescending { it.startMs }
    Column(Modifier.fillMaxWidth().padding(top = 22.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.session_events_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).semantics { heading() })
            if (events.size > EVENTS_SHOWN) {
                TextButton(onClick = { all = !all }) {
                    Text(if (all) stringResource(R.string.session_events_fewer) else stringResource(R.string.session_events_all, events.size))
                }
            }
        }
        if (events.isEmpty()) {
            Text(stringResource(R.string.session_events_none), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            for (e in if (all) events else events.take(EVENTS_SHOWN)) {
                EventRow(e, detail, units, zone) {
                    val pad = maxOf(5 * 60_000L, e.durationMs * 3)
                    onZoom(e.startMs - pad, e.endMs + pad)
                }
                HorizontalDivider(color = colors.outline)
            }
        }
    }
}

@Composable
private fun EventRow(e: SignalEvent, detail: SessionDetail, units: Units, zone: ZoneId, onClick: () -> Unit) {
    val colors = MaterialTheme.sightlineColors
    val (kind, tint) = when (e.kind) {
        EventKind.DIP -> R.string.session_event_dip to colors.healthMarginal
        EventKind.DROPOUT -> R.string.session_event_dropout to colors.healthPoor
        EventKind.TUNER_LOST -> R.string.session_event_tuner_lost to colors.healthNoLock
        EventKind.DEVICE_UNREACHABLE -> R.string.session_event_unreachable to colors.healthNoLock
        EventKind.GAP -> R.string.session_event_gap to colors.healthNoLock
    }
    val title = e.guideNumber?.let { stringResource(R.string.session_event_title, dateTime(e.startMs, zone), it, compactDuration(e.durationMs)) }
        ?: stringResource(R.string.session_event_title_device, dateTime(e.startMs, zone), compactDuration(e.durationMs))
    val detailText = when (e.kind) {
        EventKind.DIP -> e.minSnq?.let { stringResource(R.string.session_event_lowest, it) } ?: stringResource(R.string.session_event_lock_lost)
        EventKind.DROPOUT -> e.minSnq?.let { stringResource(R.string.session_event_lowest_lock_lost, it, compactDuration(e.noLockMs)) }
            ?: stringResource(R.string.session_event_lock_lost)
        EventKind.TUNER_LOST -> stringResource(R.string.session_event_tuner_lost_detail)
        EventKind.DEVICE_UNREACHABLE -> stringResource(R.string.session_event_unreachable_detail)
        EventKind.GAP -> stringResource(R.string.session_event_gap_detail)
    }
    val gust = detail.data.weatherAt(e.startMs)?.gustKph
    val second = gust?.let { stringResource(R.string.session_event_with_gusts, detailText, speedText(it, units)) } ?: detailText
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = stringResource(R.string.session_event_zoom), role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(second, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
        Text(stringResource(kind), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = tint)
    }
}
