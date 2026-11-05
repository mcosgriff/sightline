package io.cosgriff.sightline.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.cosgriff.sightline.R
import io.cosgriff.sightline.analysis.Health
import io.cosgriff.sightline.ui.theme.Numbers
import io.cosgriff.sightline.ui.theme.sightlineColors

/** One row: a channel's snq range and median. All null when it never locked or wasn't tuned. */
data class RangeRow(
    val key: String,
    val title: String,
    val subtitle: String,
    val min: Int?,
    val median: Int?,
    val max: Int?,
    /** Null when the channel couldn't be tuned. */
    val health: Health?,
)

/**
 * Every channel's min, median and max snq as a horizontal range bar (SPEC `ChannelRangeBars`), in the order given
 * (worst first, by the caller), with the [threshold] as a tick on each track, the median as a number and a
 * health chip.
 */
@Composable
fun ChannelRangeBars(rows: List<RangeRow>, threshold: Int, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.sightlineColors
    Column(modifier) {
        rows.forEach { row ->
            val described = row.median?.let {
                stringResource(R.string.range_row_description, row.title, row.min ?: it, it, row.max ?: it)
            } ?: row.title
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(0.9f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(row.title, style = Numbers.channel, maxLines = 1)
                    Text(row.subtitle, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 1)
                }
                val tint = row.health?.color() ?: colors.healthNoLock
                Canvas(Modifier.weight(1.1f).height(16.dp).clearAndSetSemantics { contentDescription = described }) {
                    val h = size.height
                    fun x(v: Int) = size.width * v.coerceIn(0, 100) / 100f
                    val track = 3.dp.toPx()
                    drawRoundRect(colors.outline, Offset(0f, (h - track) / 2), Size(size.width, track), CornerRadius(track / 2))
                    val lo = row.min
                    val hi = row.max
                    if (lo != null && hi != null) {
                        val band = 8.dp.toPx()
                        val left = x(lo) - band / 2
                        val width = (x(hi) - x(lo)) + band
                        drawRoundRect(tint.copy(alpha = 0.45f), Offset(left, (h - band) / 2), Size(width, band), CornerRadius(band / 2))
                    }
                    drawLine(colors.textSecondary, Offset(x(threshold), 0f), Offset(x(threshold), h), 1.dp.toPx())
                    row.median?.let {
                        drawCircle(colors.bg, 7.dp.toPx(), Offset(x(it), h / 2))
                        drawCircle(tint, 5.5.dp.toPx(), Offset(x(it), h / 2))
                    }
                }
                Text(
                    row.median?.toString() ?: stringResource(R.string.range_no_value),
                    style = Numbers.info,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(32.dp),
                )
                // Fixed, so every row's bar is the same length whatever its chip says.
                Row(Modifier.width(112.dp), horizontalArrangement = Arrangement.End) {
                    if (row.health != null) {
                        HealthChip(row.health)
                    } else {
                        Text(
                            stringResource(R.string.range_not_tuned),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.textSecondary,
                            modifier = Modifier.background(colors.surface, MaterialTheme.shapes.small).padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            HorizontalDivider(color = colors.outline)
        }
    }
}
