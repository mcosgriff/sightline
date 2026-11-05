package io.cosgriff.sightline.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.cosgriff.sightline.R
import io.cosgriff.sightline.analysis.ChannelChange
import io.cosgriff.sightline.analysis.ChannelDelta
import io.cosgriff.sightline.data.device.Channel
import io.cosgriff.sightline.ui.theme.Numbers
import io.cosgriff.sightline.ui.theme.sightlineColors

/**
 * One channel of a comparison (Sweep's compare view, History's Compare): its median quality before and after, and
 * the change with an arrow and a word or number, so the colour is never alone.
 */
@Composable
fun ChannelDeltaRow(d: ChannelDelta, channel: Channel?) {
    val colors = MaterialTheme.sightlineColors
    val dash = stringResource(R.string.range_no_value)
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(d.guideNumber, style = Numbers.channel)
            if (channel != null) Text(channel.guideName, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 1)
        }
        Text(d.before?.medianSnq?.toString() ?: dash, style = Numbers.info, modifier = Modifier.width(56.dp), textAlign = TextAlign.End)
        Text(d.after?.medianSnq?.toString() ?: dash, style = Numbers.info, modifier = Modifier.width(56.dp), textAlign = TextAlign.End)
        val (icon, tint) = when (d.change) {
            ChannelChange.BETTER -> Icons.Rounded.ArrowUpward to colors.healthGood
            ChannelChange.WORSE -> Icons.Rounded.ArrowDownward to colors.healthPoor
            else -> Icons.Rounded.Remove to colors.textSecondary
        }
        val text = when (d.change) {
            ChannelChange.BETTER, ChannelChange.WORSE, ChannelChange.SAME -> d.snqDelta?.let { signed(it) }
                ?: stringResource(if (d.change == ChannelChange.BETTER) R.string.sweep_change_locks else if (d.change == ChannelChange.WORSE) R.string.sweep_change_lost else R.string.sweep_change_same)
            ChannelChange.NEW -> stringResource(R.string.sweep_change_new)
            ChannelChange.GONE -> stringResource(R.string.sweep_change_gone)
            ChannelChange.NOT_COMPARABLE -> stringResource(R.string.sweep_change_not_comparable)
        }
        Row(Modifier.width(96.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Text(text, style = Numbers.info, color = tint, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
private fun signed(delta: Int) = when {
    delta > 0 -> stringResource(R.string.sweep_delta_up, delta)
    delta < 0 -> stringResource(R.string.sweep_delta_down, -delta)
    else -> stringResource(R.string.sweep_delta_zero)
}
