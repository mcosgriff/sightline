package io.cosgriff.sightline.ui.screens.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CompareArrows
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.cosgriff.sightline.R
import io.cosgriff.sightline.data.db.SessionKind
import io.cosgriff.sightline.data.session.HistoryRow
import io.cosgriff.sightline.ui.components.HealthChip
import io.cosgriff.sightline.ui.components.dateHour
import io.cosgriff.sightline.ui.components.sessionLength
import io.cosgriff.sightline.ui.theme.sightlineColors
import java.time.ZoneId

data class HistoryActions(
    val onOpen: (Long) -> Unit,
    val onToggle: (Long) -> Unit,
    val onClearSelection: () -> Unit,
    val onDelete: () -> Unit,
    /** The two selected sessions, to compare. */
    val onCompare: () -> Unit = {},
)

@Composable
fun HistoryScreen(onOpen: (Long) -> Unit, onCompare: (Long, Long) -> Unit, viewModel: HistoryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HistoryContent(
        state = state,
        zone = ZoneId.systemDefault(),
        actions = HistoryActions(onOpen, viewModel::toggle, viewModel::clearSelection, viewModel::deleteSelected) {
            state.selected.toList().takeIf { it.size == 2 }?.let { (a, b) -> onCompare(a, b) }
        },
    )
}

/**
 * History (SPEC Screens › 9): sessions newest first, each with its kind, start, length, channels, a sparkline of
 * quality and a health chip. A tap opens it; press and hold selects; selected sessions can be deleted, and two
 * can be compared.
 */
@Composable
fun HistoryContent(state: HistoryUiState, zone: ZoneId, actions: HistoryActions, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.sightlineColors
    var confirm by rememberSaveable { mutableStateOf(false) }
    val selecting = state.selected.isNotEmpty()
    Column(modifier.fillMaxSize()) {
        if (selecting) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.onClearSelection) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.history_clear_selection))
                }
                Column(Modifier.weight(1f)) {
                    Text(pluralStringResource(R.plurals.history_selected, state.selected.size, state.selected.size), style = MaterialTheme.typography.titleLarge)
                    if (state.selected.size == 1) Text(stringResource(R.string.history_compare_hint), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                }
                if (state.selected.size == 2) {
                    IconButton(onClick = actions.onCompare) {
                        Icon(Icons.AutoMirrored.Outlined.CompareArrows, contentDescription = stringResource(R.string.history_compare))
                    }
                }
                IconButton(onClick = { confirm = true }) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.history_delete))
                }
            }
        } else {
            Column(Modifier.padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.history_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                if (state.rows.isNotEmpty()) {
                    Text(stringResource(R.string.history_select_hint), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                }
            }
        }
        if (!state.loading && state.rows.isEmpty()) {
            Text(
                stringResource(R.string.history_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            items(state.rows, key = { it.info.id }) { row ->
                val id = row.info.id
                SessionRow(
                    row = row,
                    deviceName = if (state.manyDevices) state.deviceNames[row.info.deviceId] ?: row.info.deviceId else null,
                    selected = id in state.selected,
                    zone = zone,
                    onClick = { if (selecting) actions.onToggle(id) else actions.onOpen(id) },
                    onLongClick = { actions.onToggle(id) },
                )
                HorizontalDivider(color = colors.outline)
            }
        }
    }
    if (confirm) {
        val n = state.selected.size
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(pluralStringResource(R.plurals.history_delete_title, n, n)) },
            text = { Text(stringResource(R.string.history_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    actions.onDelete()
                }) { Text(stringResource(R.string.history_delete_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.history_delete_cancel)) } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(row: HistoryRow, deviceName: String?, selected: Boolean, zone: ZoneId, onClick: () -> Unit, onLongClick: () -> Unit) {
    val colors = MaterialTheme.sightlineColors
    val info = row.info
    val summary = row.summary
    val kind = stringResource(
        when (info.kind) {
            SessionKind.MONITOR -> R.string.history_kind_monitor
            SessionKind.SWEEP -> R.string.history_kind_sweep
            SessionKind.LIVE -> R.string.history_kind_live
            SessionKind.AIM -> R.string.history_kind_aim
        },
    )
    val start = dateHour(info.startedAt, zone)
    val title = deviceName?.let { stringResource(R.string.history_row_when_device, kind, start, it) } ?: stringResource(R.string.history_row_when, kind, start)
    val length = sessionLength((info.endedAt ?: info.startedAt) - info.startedAt)
    val channels = when (info.channels.size) {
        1 -> info.channels.single()
        2 -> stringResource(R.string.session_and, info.channels[0], info.channels[1])
        else -> pluralStringResource(R.plurals.session_channels, info.channels.size, info.channels.size)
    }
    val detail = when {
        summary == null -> stringResource(R.string.history_row_detail_dips, length, channels, stringResource(R.string.history_row_summarising))
        summary.readings == 0 -> stringResource(R.string.history_row_detail_dips, length, channels, stringResource(R.string.history_row_no_readings))
        info.kind == SessionKind.MONITOR -> stringResource(R.string.history_row_detail_dips, length, channels, pluralStringResource(R.plurals.history_row_dips, summary.dips, summary.dips))
        else -> stringResource(R.string.history_row_detail, length, channels)
    }
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 64.dp)
            .background(if (selected) colors.accent.copy(alpha = 0.12f) else colors.bg)
            .combinedClickable(
                onClickLabel = stringResource(R.string.history_open),
                onLongClickLabel = stringResource(R.string.history_select),
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .semantics { this.selected = selected }
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (selected) Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = colors.accent)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
        if (summary != null && summary.sparkline.any { it != null }) MiniSparkline(summary.sparkline)
        summary?.health?.let { HealthChip(it) }
    }
}

/** The lowest quality of each stretch of a session, 0–100, as a small line; nothing where nothing was read. */
@Composable
private fun MiniSparkline(points: List<Int?>) {
    val colors = MaterialTheme.sightlineColors
    val described = stringResource(R.string.history_sparkline_description)
    Canvas(Modifier.width(56.dp).height(24.dp).semantics { contentDescription = described }) {
        val step = size.width / (points.size - 1).coerceAtLeast(1)
        val path = Path()
        var drawing = false
        points.forEachIndexed { i, v ->
            if (v == null) {
                drawing = false
            } else {
                val x = i * step
                val y = size.height * (1 - v / 100f)
                if (drawing) path.lineTo(x, y) else path.moveTo(x, y)
                drawing = true
            }
        }
        drawPath(path, colors.accent, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
