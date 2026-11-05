package io.cosgriff.sightline.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.cosgriff.sightline.R
import io.cosgriff.sightline.analysis.Best
import io.cosgriff.sightline.analysis.Health
import io.cosgriff.sightline.analysis.Thresholds
import io.cosgriff.sightline.ui.theme.Numbers
import io.cosgriff.sightline.ui.theme.sightlineColors

/** The three numbers a reading gives. Null values draw empty gauges (no reading yet, or no lock). */
data class GaugeValues(val ss: Int?, val snq: Int?, val seq: Int?)

/**
 * Signal quality large, strength and symbol quality smaller beneath (SPEC `TriGauge`), each coloured by its band
 * and marked with its session best. In a wide space (landscape) the three sit in one row.
 */
@Composable
fun TriGauge(values: GaugeValues, best: Best?, thresholds: Thresholds, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val wide = maxWidth > 600.dp
        val main: @Composable () -> Unit = { QualityGauge(values.snq, best?.snq, thresholds) }
        val small: @Composable (Modifier) -> Unit = { m ->
            SmallGauge(
                value = values.ss, best = best?.ss, health = values.ss?.let(thresholds::ss),
                label = stringResource(R.string.gauge_strength),
                description = values.ss?.let { stringResource(R.string.gauge_strength_description, it) }
                    ?: stringResource(R.string.gauge_strength_none),
                modifier = m,
            )
            SmallGauge(
                value = values.seq, best = best?.seq, health = values.seq?.let(thresholds::seq),
                label = stringResource(R.string.gauge_seq),
                description = values.seq?.let { stringResource(R.string.gauge_seq_description, it) }
                    ?: stringResource(R.string.gauge_seq_none),
                modifier = m,
            )
        }
        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                main()
                small(Modifier.weight(1f))
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                main()
                Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { small(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun QualityGauge(snq: Int?, best: Int?, thresholds: Thresholds) {
    val colors = MaterialTheme.sightlineColors
    val description = when {
        snq == null -> stringResource(R.string.gauge_snq_none)
        best == null -> stringResource(R.string.gauge_snq_description, snq)
        else -> stringResource(R.string.gauge_snq_description_best, snq, best)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        ArcGauge(
            value = snq,
            color = (snq?.let(thresholds::snq) ?: Health.NO_LOCK).color(),
            trackColor = colors.outline,
            markColor = colors.textPrimary,
            holeColor = colors.bg,
            stroke = 18.dp,
            description = description,
            threshold = thresholds.snqGood,
            peak = best,
            modifier = Modifier.size(width = 240.dp, height = 206.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.offset(y = (-6).dp)) {
                Text(snq?.toString() ?: stringResource(R.string.gauge_no_value), style = Numbers.gaugeLive, color = colors.textPrimary)
                Text(stringResource(R.string.gauge_snq), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            }
        }
        Text(
            best?.let { stringResource(R.string.gauge_best, it) } ?: "",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            modifier = Modifier.offset(y = (-14).dp),
        )
    }
}

@Composable
private fun SmallGauge(value: Int?, best: Int?, health: Health?, label: String, description: String, modifier: Modifier) {
    val colors = MaterialTheme.sightlineColors
    Column(
        modifier = modifier
            .background(colors.surface, MaterialTheme.shapes.medium)
            .padding(top = 10.dp, bottom = 12.dp, start = 8.dp, end = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ArcGauge(
            value = value,
            color = (health ?: Health.NO_LOCK).color(),
            trackColor = colors.outline,
            markColor = colors.textPrimary,
            holeColor = colors.surface,
            stroke = 9.dp,
            description = description,
            peak = best,
            modifier = Modifier.size(width = 96.dp, height = 80.dp),
        ) {
            Text(
                value?.toString() ?: stringResource(R.string.gauge_no_value),
                style = Numbers.gaugeSmall,
                color = colors.textPrimary,
                modifier = Modifier.offset(y = (-4).dp),
            )
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
    }
}
