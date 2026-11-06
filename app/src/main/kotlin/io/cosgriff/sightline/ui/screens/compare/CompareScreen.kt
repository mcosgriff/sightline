package io.cosgriff.sightline.ui.screens.compare

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.cosgriff.sightline.R
import io.cosgriff.sightline.data.session.SessionDetail
import io.cosgriff.sightline.ui.components.ChannelDeltaRow
import io.cosgriff.sightline.ui.components.compactDuration
import io.cosgriff.sightline.ui.components.dateHour
import io.cosgriff.sightline.ui.components.sessionLength
import io.cosgriff.sightline.ui.theme.Numbers
import io.cosgriff.sightline.ui.theme.sightlineColors
import java.time.ZoneId

@Composable
fun CompareScreen(a: Long, b: Long, onBack: () -> Unit) {
    val viewModel = hiltViewModel<CompareViewModel, CompareViewModel.Factory>(key = "compare-$a-$b") { it.create(a, b) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    CompareContent(state, ZoneId.systemDefault(), onBack)
}

/**
 * Two sessions side by side (SPEC Screens › 9): the summary figures, dips per hour (sessions are rarely the same
 * length), and each channel's median quality before and after.
 */
@Composable
fun CompareContent(state: CompareUiState, zone: ZoneId, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.sightlineColors
    val before = state.before
    val after = state.after
    Column(modifier.fillMaxSize().background(colors.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.compare_back))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(stringResource(R.string.compare_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                if (before != null && after != null) {
                    Text(
                        stringResource(R.string.compare_subtitle, dateHour(before.info.startedAt, zone), dateHour(after.info.startedAt, zone)),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
            }
        }
        when {
            state.loading -> Note(stringResource(R.string.compare_loading))
            before == null || after == null -> Note(stringResource(R.string.compare_missing))
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Figures(before, after)
                val comparison = state.comparison
                if (comparison != null && comparison.deltas.isNotEmpty()) {
                    Text(
                        stringResource(R.string.compare_channels),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 22.dp, bottom = 4.dp).semantics { heading() },
                    )
                    HeaderRow(stringResource(R.string.sweep_compare_channel), valueWidth = 56, change = true)
                    HorizontalDivider(color = colors.outline)
                    for (d in comparison.deltas) {
                        ChannelDeltaRow(d, after.channels[d.guideNumber] ?: before.channels[d.guideNumber])
                        HorizontalDivider(color = colors.outline)
                    }
                }
                if (before.readingsDeleted || after.readingsDeleted) Note(stringResource(R.string.compare_readings_deleted))
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.sightlineColors.textSecondary, modifier = Modifier.padding(20.dp))
}

/** "What", "Before" and "Now" [valueWidth] wide each, and, when [change], a "Change" column as ChannelDeltaRow's. */
@Composable
private fun HeaderRow(first: String, valueWidth: Int, change: Boolean) {
    val colors = MaterialTheme.sightlineColors
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(first, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.sweep_compare_before), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, modifier = Modifier.width(valueWidth.dp), textAlign = TextAlign.End)
        Text(stringResource(R.string.sweep_compare_after), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, modifier = Modifier.width(valueWidth.dp), textAlign = TextAlign.End)
        if (change) {
            Text(stringResource(R.string.sweep_compare_change), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, modifier = Modifier.width(96.dp), textAlign = TextAlign.End)
        }
    }
}

@Composable
private fun Figures(before: SessionDetail, after: SessionDetail) {
    val colors = MaterialTheme.sightlineColors
    val dash = stringResource(R.string.session_dash)
    val good: @Composable (SessionDetail) -> String = { d -> d.stats.goodShare?.let { stringResource(R.string.session_percent, it * 100) } ?: dash }
    val rate: @Composable (SessionDetail) -> String = { d ->
        val hours = (d.data.endMs - d.data.startMs) / 3_600_000.0
        if (hours > 0) stringResource(R.string.compare_rate, d.stats.dips / hours) else dash
    }
    val errorRate: @Composable (SessionDetail) -> String = { d ->
        val hours = (d.data.endMs - d.data.startMs) / 3_600_000.0
        if (d.stats.errors.hasCounters && hours > 0) stringResource(R.string.compare_error_rate_value, d.stats.errors.total.transport / hours) else dash
    }
    val dropout: @Composable (SessionDetail) -> String = { d -> d.stats.longestDropoutMs?.let { compactDuration(it) } ?: stringResource(R.string.session_none) }
    val rows = listOf(
        stringResource(R.string.compare_length) to listOf(sessionLength(before.data.endMs - before.data.startMs), sessionLength(after.data.endMs - after.data.startMs)),
        stringResource(R.string.session_card_good) to listOf(good(before), good(after)),
        stringResource(R.string.session_card_dips) to listOf(before.stats.dips.toString(), after.stats.dips.toString()),
        stringResource(R.string.compare_dip_rate) to listOf(rate(before), rate(after)),
        stringResource(R.string.session_card_dropout) to listOf(dropout(before), dropout(after)),
        stringResource(R.string.session_card_median) to listOf(before.stats.medianSnq?.toString() ?: dash, after.stats.medianSnq?.toString() ?: dash),
        stringResource(R.string.compare_error_rate) to listOf(errorRate(before), errorRate(after)),
        // A firmware update between the two is a change worth seeing next to the figures.
        stringResource(R.string.compare_firmware) to listOf(before.info.firmware ?: dash, after.info.firmware ?: dash),
    )
    HeaderRow(stringResource(R.string.compare_figure), valueWidth = 72, change = false)
    HorizontalDivider(color = colors.outline)
    for ((label, values) in rows) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            for (v in values) Text(v, style = Numbers.info, modifier = Modifier.width(72.dp), textAlign = TextAlign.End)
        }
        HorizontalDivider(color = colors.outline)
    }
}
