package io.cosgriff.sightline.ui.screens.live

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.cosgriff.sightline.R
import io.cosgriff.sightline.data.device.Channel
import io.cosgriff.sightline.ui.theme.Numbers
import io.cosgriff.sightline.ui.theme.sightlineColors

/**
 * The channels [query] picks out: guide numbers that start with it first (`7` finds 7.1 and 7.2, `7-1` and `7 1`
 * mean 7.1), then names that contain it. A blank query keeps them all, in lineup order.
 */
internal fun List<Channel>.matching(query: String): List<Channel> {
    val q = query.trim()
    if (q.isEmpty()) return this
    val number = q.replace('-', '.').replace(' ', '.')
    val byNumber = filter { it.guideNumber.startsWith(number) }
    val byName = filter { it !in byNumber && it.guideName.contains(q, ignoreCase = true) }
    return byNumber + byName
}

/** Jump to any channel without stepping through the ones in between. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChannelPicker(channels: List<Channel>, current: Channel, onPick: (Channel) -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.sightlineColors
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(channels, query) { channels.matching(query) }
    val focus = remember { FocusRequester() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            Text(
                stringResource(R.string.live_picker_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focus),
                placeholder = { Text(stringResource(R.string.live_picker_search)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { shown.firstOrNull()?.let(onPick) }),
                shape = MaterialTheme.shapes.small,
            )
            if (shown.isEmpty()) {
                Text(
                    stringResource(R.string.live_picker_none, query.trim()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }
            val list = rememberLazyListState(
                initialFirstVisibleItemIndex = channels.indexOfFirst { it.guideNumber == current.guideNumber }.coerceAtLeast(0),
            )
            LazyColumn(Modifier.fillMaxWidth(), state = list) {
                items(shown, key = { it.guideNumber }) { channel ->
                    val isCurrent = channel.guideNumber == current.guideNumber
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .semantics { selected = isCurrent }
                            .clickable { onPick(channel) }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(channel.guideNumber, style = Numbers.info, modifier = Modifier.widthIn(min = 56.dp))
                        Text(channel.guideName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, modifier = Modifier.weight(1f))
                        if (channel.atsc3) {
                            Text(stringResource(R.string.live_atsc3), style = MaterialTheme.typography.labelSmall, color = colors.accent)
                        }
                        if (isCurrent) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = stringResource(R.string.live_picker_current),
                                tint = colors.accent,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}
