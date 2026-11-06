package io.cosgriff.sightline.ui.screens.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.cosgriff.sightline.R
import io.cosgriff.sightline.data.device.TunerUse
import io.cosgriff.sightline.ui.components.LocalNetworkCard
import io.cosgriff.sightline.ui.components.rememberLocalNetworkAccess
import io.cosgriff.sightline.ui.theme.Numbers
import io.cosgriff.sightline.ui.theme.sightlineColors

@Composable
fun DevicesScreen(
    onOpenLive: () -> Unit,
    onOpenProtocolDebug: (() -> Unit)?,
    onOpenDiagnostics: (deviceId: String) -> Unit = {},
    viewModel: DevicesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val network = rememberLocalNetworkAccess()
    if (network.granted) {
        LifecycleStartEffect(viewModel) {
            viewModel.start()
            onStopOrDispose { viewModel.stop() }
        }
    }
    DevicesContent(
        state = state,
        nowMs = state.nowMs,
        networkCard = if (network.granted) null else ({ LocalNetworkCard(network) }),
        onRescan = viewModel::rescan,
        onAdd = viewModel::add,
        onOpen = { viewModel.openLive(it, onOpenLive) },
        onOpenProtocolDebug = onOpenProtocolDebug,
        onOpenDiagnostics = onOpenDiagnostics,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesContent(
    state: DevicesUiState,
    nowMs: Long,
    networkCard: (@Composable () -> Unit)?,
    onRescan: () -> Unit,
    onAdd: (String) -> Unit,
    onOpen: (String) -> Unit,
    onOpenProtocolDebug: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onOpenDiagnostics: (deviceId: String) -> Unit = {},
) {
    val colors = MaterialTheme.sightlineColors
    PullToRefreshBox(isRefreshing = state.scanning, onRefresh = onRescan, modifier = modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Header(state, nowMs, onRescan) }
            if (networkCard != null) item { networkCard() }
            items(state.cards, key = { it.device.deviceId }) { card -> DeviceCardView(card, onDiagnostics = { onOpenDiagnostics(card.device.deviceId) }) { onOpen(card.device.deviceId) } }
            if (state.cards.isEmpty() && !state.scanning && networkCard == null) item { EmptyState(onRescan) }
            item { AddByAddress(state, onAdd) }
            item { Legend() }
            if (onOpenProtocolDebug != null) {
                item {
                    OutlinedButton(onClick = onOpenProtocolDebug) { Text(stringResource(R.string.devices_open_protocol_debug)) }
                }
            }
        }
    }
}

@Composable
private fun Header(state: DevicesUiState, nowMs: Long, onRescan: () -> Unit) {
    val colors = MaterialTheme.sightlineColors
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.devices_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            val found = state.cards.count { !it.device.demo }
            val subtitle = when {
                state.scanning -> stringResource(R.string.devices_scanning)
                state.scannedAt == null -> stringResource(R.string.devices_not_scanned)
                else -> {
                    val minutes = ((nowMs - state.scannedAt) / 60_000).toInt()
                    val count = pluralStringResource(R.plurals.devices_found, found, found)
                    if (minutes < 1) stringResource(R.string.devices_scanned_now, count)
                    else pluralStringResource(R.plurals.devices_scanned_ago, minutes, count, minutes)
                }
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
        IconButton(onClick = onRescan, enabled = !state.scanning) {
            Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.devices_rescan))
        }
    }
}

@Composable
private fun DeviceCardView(card: DeviceCard, onDiagnostics: () -> Unit, onOpen: () -> Unit) {
    val colors = MaterialTheme.sightlineColors
    val device = card.device
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, MaterialTheme.shapes.medium)
            .border(1.dp, colors.outline, MaterialTheme.shapes.medium)
            .clickable(onClickLabel = stringResource(R.string.devices_open_live), onClick = onOpen)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(device.friendlyName, style = MaterialTheme.typography.titleMedium)
                if (device.model.isNotEmpty()) {
                    Text(
                        stringResource(R.string.devices_model_firmware, device.model, device.firmware),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
                Text(
                    stringResource(R.string.devices_address_id, device.address, device.deviceId),
                    style = Numbers.inline,
                    color = colors.textSecondary,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Status(card)
                IconButton(onClick = onDiagnostics) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = stringResource(R.string.devices_open_diagnostics, device.friendlyName),
                        tint = colors.textSecondary,
                    )
                }
            }
        }
        val tuners = card.tuners
        if (tuners != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tuners.forEachIndexed { i, use -> TunerCell(i, use, Modifier.weight(1f)) }
            }
            tuners.forEachIndexed { i, use ->
                if (use is TunerUse.Other) {
                    Text(
                        use.holder?.let { stringResource(R.string.devices_tuner_held_by, i, it) }
                            ?: stringResource(R.string.devices_tuner_held_unknown, i),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
            }
        } else if (card.online == false) {
            Text(stringResource(R.string.devices_offline_note), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
    }
}

@Composable
private fun Status(card: DeviceCard) {
    val colors = MaterialTheme.sightlineColors
    val (text, color) = when {
        card.device.demo -> R.string.devices_demo to colors.accent
        card.online == true -> R.string.devices_online to colors.healthGood
        card.online == false -> R.string.devices_offline to colors.healthNoLock
        else -> return
    }
    Text(
        stringResource(text),
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier.background(color.copy(alpha = 0.12f), MaterialTheme.shapes.small).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun useLook(use: TunerUse): Pair<Int, Color> {
    val colors = MaterialTheme.sightlineColors
    return when (use) {
        TunerUse.Idle -> R.string.devices_tuner_idle to colors.healthGood
        TunerUse.Sightline -> R.string.devices_tuner_sightline to colors.accent
        is TunerUse.Other -> R.string.devices_tuner_in_use to colors.healthNoLock
        TunerUse.Unknown -> R.string.devices_tuner_unknown to colors.outline
    }
}

@Composable
private fun TunerCell(index: Int, use: TunerUse, modifier: Modifier) {
    val colors = MaterialTheme.sightlineColors
    val (word, dot) = useLook(use)
    val name = stringResource(R.string.devices_tuner, index)
    val wordText = stringResource(word)
    Column(
        modifier = modifier
            .background(colors.bg, MaterialTheme.shapes.small)
            .padding(8.dp)
            .clearAndSetSemantics { contentDescription = "$name, $wordText" },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(name, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.size(8.dp).background(dot, CircleShape))
            Text(wordText, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@Composable
private fun EmptyState(onRetry: () -> Unit) {
    val colors = MaterialTheme.sightlineColors
    Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.devices_empty_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.devices_empty_body), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        Button(onClick = onRetry) { Text(stringResource(R.string.devices_retry)) }
    }
}

@Composable
private fun AddByAddress(state: DevicesUiState, onAdd: (String) -> Unit) {
    val colors = MaterialTheme.sightlineColors
    var text by rememberSaveable { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRoundRect(
                    color = colors.outline,
                    cornerRadius = CornerRadius(18.dp.toPx()),
                    style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                )
            }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.devices_add_title), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.devices_add_hint), style = MaterialTheme.typography.bodyMedium, maxLines = 1) },
                textStyle = MaterialTheme.typography.bodyMedium,
                singleLine = true,
                isError = state.addFailure != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onAdd(text) }),
                shape = MaterialTheme.shapes.small,
                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = colors.outline, unfocusedContainerColor = colors.bg, focusedContainerColor = colors.bg),
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = { onAdd(text) },
                enabled = text.isNotBlank() && !state.adding,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(if (state.adding) R.string.devices_adding else R.string.devices_add)) }
        }
        val failure = state.addFailure
        Text(
            stringResource(
                when (failure) {
                    null -> R.string.devices_add_note
                    AddFailure.BAD_ADDRESS -> R.string.devices_add_bad_address
                    AddFailure.NOT_FOUND -> R.string.devices_add_not_found
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = if (failure == null) colors.textSecondary else colors.healthPoor,
        )
    }
}

@Composable
private fun Legend() {
    val colors = MaterialTheme.sightlineColors
    Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        listOf(
            R.string.devices_legend_idle to colors.healthGood,
            R.string.devices_legend_sightline to colors.accent,
            R.string.devices_legend_other to colors.healthNoLock,
        ).forEach { (text, dot) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(8.dp).background(dot, CircleShape))
                Text(stringResource(text), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
            }
        }
    }
}
