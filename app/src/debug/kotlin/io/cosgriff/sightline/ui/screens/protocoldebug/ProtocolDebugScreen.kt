package io.cosgriff.sightline.ui.screens.protocoldebug

import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.cosgriff.sightline.R
import io.cosgriff.sightline.protocol.LineupEntry
import io.cosgriff.sightline.protocol.LostReason
import io.cosgriff.sightline.ui.theme.Numbers
import io.cosgriff.sightline.ui.theme.sightlineColors

@Composable
fun ProtocolDebugScreen(viewModel: ProtocolDebugViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Android 17 blocks the local network until the user allows it (Nearby devices). Earlier versions don't ask.
    var granted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 37 ||
                ContextCompat.checkSelfPermission(context, LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    ProtocolDebugContent(
        state = state,
        needsLocalNetwork = !granted,
        onAllowLocalNetwork = { launcher.launch(LOCAL_NETWORK) },
        onScan = viewModel::scan,
        onSelect = viewModel::select,
        onClaim = viewModel::claim,
        onRelease = viewModel::release,
    )
}

/** `Manifest.permission.ACCESS_LOCAL_NETWORK`, API 37. Spelled out so lint doesn't treat it as an inlined new API. */
private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProtocolDebugContent(
    state: ProtocolDebugState,
    needsLocalNetwork: Boolean,
    onAllowLocalNetwork: () -> Unit,
    onScan: () -> Unit,
    onSelect: (String) -> Unit,
    onClaim: (LineupEntry) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.sightlineColors
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.protocoldebug_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Text(stringResource(R.string.protocoldebug_note), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            }
        }
        if (needsLocalNetwork) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = colors.surface)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.protocoldebug_permission), style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = onAllowLocalNetwork) { Text(stringResource(R.string.protocoldebug_permission_allow)) }
                    }
                }
            }
        }
        item {
            Button(onClick = onScan, enabled = !state.scanning) {
                Text(stringResource(if (state.scanning) R.string.protocoldebug_scanning else R.string.protocoldebug_scan))
            }
        }
        items(state.devices, key = { it.device.deviceId }) { item ->
            val selected = item.device.deviceId == state.selectedId
            Card(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onSelect(item.device.deviceId) },
                colors = CardDefaults.cardColors(containerColor = colors.surface),
                border = if (selected) BorderStroke(1.dp, colors.accent) else null,
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(item.device.deviceId, style = Numbers.inline)
                        Text(
                            pluralStringResource(R.plurals.protocoldebug_tuners, item.device.tunerCount, item.device.tunerCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                        )
                        Text(item.device.baseUrl, style = Numbers.inline, color = colors.textSecondary)
                    }
                    if (item.demo) AssistChip(onClick = { onSelect(item.device.deviceId) }, label = { Text(stringResource(R.string.protocoldebug_demo)) })
                }
            }
        }
        if (state.selectedId != null) {
            item { Text(stringResource(R.string.protocoldebug_status_heading), style = MaterialTheme.typography.titleMedium) }
            items(state.tunerLines, key = { "t${it.tuner}" }) { line ->
                Column {
                    Text(stringResource(R.string.protocoldebug_tuner, line.tuner), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                    Text(
                        line.raw ?: line.error ?: stringResource(R.string.protocoldebug_no_answer),
                        style = Numbers.inline,
                    )
                }
            }
            item { ClaimSection(state.claim, onRelease) }
            if (state.lineup.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.protocoldebug_claim_heading), style = MaterialTheme.typography.titleMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.lineup.forEach { entry ->
                                OutlinedButton(onClick = { onClaim(entry) }) {
                                    Text(stringResource(R.string.protocoldebug_channel, entry.guideNumber, entry.guideName))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClaimSection(claim: ClaimView, onRelease: () -> Unit) {
    val text = when (claim) {
        ClaimView.None -> stringResource(R.string.protocoldebug_claim_none)
        ClaimView.Claiming -> stringResource(R.string.protocoldebug_claiming)
        is ClaimView.Held -> stringResource(
            if (claim.stream) R.string.protocoldebug_held_stream else R.string.protocoldebug_held_lockkey,
            claim.tuner,
        )
        is ClaimView.Lost -> when (val reason = claim.reason) {
            is LostReason.TakenBy -> reason.holder?.let { stringResource(R.string.protocoldebug_lost_taken_by, claim.tuner, it) }
                ?: stringResource(R.string.protocoldebug_lost_taken, claim.tuner)
            is LostReason.ChannelChanged -> stringResource(R.string.protocoldebug_lost_retuned, claim.tuner)
            LostReason.StreamEnded -> stringResource(R.string.protocoldebug_lost_stream, claim.tuner)
            LostReason.Released -> stringResource(R.string.protocoldebug_claim_none)
        }
        is ClaimView.Failed -> stringResource(
            when (claim.why) {
                ClaimFailure.NONE_FREE -> R.string.protocoldebug_failed_none_free
                ClaimFailure.UNKNOWN_CHANNEL -> R.string.protocoldebug_failed_unknown
                ClaimFailure.UNREACHABLE -> R.string.protocoldebug_failed_unreachable
            },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        if (claim is ClaimView.Held) {
            claim.raw?.let { Text(it, style = Numbers.inline) }
            Button(onClick = onRelease) { Text(stringResource(R.string.protocoldebug_release, claim.tuner)) }
        }
    }
}
