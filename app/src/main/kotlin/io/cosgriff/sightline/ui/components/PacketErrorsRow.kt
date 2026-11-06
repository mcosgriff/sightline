package io.cosgriff.sightline.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import io.cosgriff.sightline.R
import io.cosgriff.sightline.analysis.PacketErrors
import io.cosgriff.sightline.ui.theme.Numbers
import io.cosgriff.sightline.ui.theme.sightlineColors

/**
 * The tuner's packet error counts (SPEC "Packet errors") under [title]: a check and "None" when all are zero,
 * otherwise a warning icon and the four counts, with "Rising now" when [rising]. Not colour alone: the icon and the
 * words say it too.
 */
@Composable
fun PacketErrorsRow(title: String, errors: PacketErrors, modifier: Modifier = Modifier, rising: Boolean = false) {
    val colors = MaterialTheme.sightlineColors
    val tint = if (errors.any) colors.healthMarginal else colors.healthGood
    val description = if (errors.any) {
        stringResource(
            if (rising) R.string.packet_errors_description_rising else R.string.packet_errors_description,
            title, errors.transport, errors.crc, errors.resyncs, errors.network,
        )
    } else {
        stringResource(R.string.packet_errors_none_description, title)
    }
    Row(
        modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(if (errors.any) Icons.Rounded.Warning else Icons.Rounded.CheckCircle, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, modifier = Modifier.weight(1f))
                if (rising) Text(stringResource(R.string.packet_errors_rising), style = MaterialTheme.typography.labelSmall, color = colors.healthMarginal)
            }
            if (errors.any) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Count(stringResource(R.string.packet_errors_transport), errors.transport)
                    Count(stringResource(R.string.packet_errors_crc), errors.crc)
                    Count(stringResource(R.string.packet_errors_resyncs), errors.resyncs)
                    Count(stringResource(R.string.packet_errors_network), errors.network)
                }
            } else {
                Text(stringResource(R.string.packet_errors_none), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun Count(label: String, value: Long) {
    val colors = MaterialTheme.sightlineColors
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value.toString(), style = Numbers.info, color = if (value > 0) colors.healthMarginal else colors.textPrimary)
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary, modifier = Modifier.padding(start = 4.dp, bottom = 1.dp))
    }
}
