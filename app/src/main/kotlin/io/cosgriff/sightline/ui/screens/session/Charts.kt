package io.cosgriff.sightline.ui.screens.session

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModel
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.ColumnCartesianLayerModel
import com.patrykandpatrick.vico.compose.cartesian.layer.CartesianLayerPadding
import com.patrykandpatrick.vico.compose.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.LineComponent
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import io.cosgriff.sightline.R
import io.cosgriff.sightline.analysis.Health
import io.cosgriff.sightline.analysis.HourCell
import io.cosgriff.sightline.analysis.SnqHistogram
import io.cosgriff.sightline.analysis.Thresholds
import io.cosgriff.sightline.analysis.WeatherHour
import io.cosgriff.sightline.data.settings.Units
import io.cosgriff.sightline.ui.components.color
import io.cosgriff.sightline.ui.components.hour
import io.cosgriff.sightline.ui.components.speed
import io.cosgriff.sightline.ui.components.temperature
import io.cosgriff.sightline.ui.theme.Numbers
import io.cosgriff.sightline.ui.theme.sightlineColors
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * The weather panel under the timeline (SPEC `SignalTimeline`: "weather as a second panel of bars"): one bar per
 * hour of [startMs]..[endMs], lined up with the chart above. Gusts over [gustLineKph] are drawn in the marginal
 * colour, as in the mockup, which also names the line in the finding.
 */
@Composable
fun WeatherPanel(
    hours: List<WeatherHour>,
    metric: WeatherMetric,
    startMs: Long,
    endMs: Long,
    units: Units,
    gustLineKph: Double,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.sightlineColors
    val values = hours.mapNotNull { h ->
        val v = when (metric) {
            WeatherMetric.GUSTS -> h.gustKph?.let { units.speed(it).toDouble() }
            WeatherMetric.TEMPERATURE -> h.tempC?.let { units.temperature(it).toDouble() }
            WeatherMetric.RAIN -> h.precipMm
        }
        v?.let { Triple(h, it, metric == WeatherMetric.GUSTS && (h.gustKph ?: 0.0) > gustLineKph) }
    }
    val axis = stringResource(
        when (metric) {
            WeatherMetric.GUSTS -> if (units == Units.METRIC) R.string.session_weather_axis_gusts_kph else R.string.session_weather_axis_gusts_mph
            WeatherMetric.TEMPERATURE -> if (units == Units.METRIC) R.string.session_weather_axis_temp_c else R.string.session_weather_axis_temp_f
            WeatherMetric.RAIN -> R.string.session_weather_axis_rain
        },
    )
    val metricName = stringResource(
        when (metric) {
            WeatherMetric.GUSTS -> R.string.session_weather_gusts
            WeatherMetric.TEMPERATURE -> R.string.session_weather_temperature
            WeatherMetric.RAIN -> R.string.session_weather_rain
        },
    )
    val top = values.maxOfOrNull { it.second }
    val topText = top?.let { if (metric == WeatherMetric.RAIN) stringResource(R.string.session_mm, it) else it.roundToInt().toString() }.orEmpty()
    val described = stringResource(R.string.session_weather_description, metricName, topText)
    // Temperatures can be below zero: bars rise from the coldest hour shown.
    val floor = if (metric == WeatherMetric.TEMPERATURE) (values.minOfOrNull { it.second } ?: 0.0) - 2 else 0.0
    val ceiling = maxOf((top ?: 1.0), floor + 1)
    Column(modifier.semantics(mergeDescendants = true) { contentDescription = described }) {
        Text(axis, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
        Canvas(Modifier.fillMaxWidth().height(48.dp).padding(top = 4.dp)) {
            val span = (endMs - startMs).coerceAtLeast(1).toFloat()
            val gap = 2.dp.toPx()
            for ((h, v, over) in values) {
                val left = size.width * ((h.hourMs - startMs) / span)
                val right = size.width * ((h.hourMs + WeatherHour.HOUR_MS - startMs) / span)
                val l = left.coerceIn(0f, size.width)
                val r = right.coerceIn(0f, size.width)
                if (r - l <= gap) continue
                val height = size.height * ((v - floor) / (ceiling - floor)).toFloat().coerceIn(0.02f, 1f)
                val color = if (over) colors.healthMarginal.copy(alpha = 0.7f) else colors.weatherBar
                drawRect(color, Offset(l + gap / 2, size.height - height), Size(r - l - gap, height))
            }
            drawLine(colors.outline, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        }
    }
}

/** How dark the worst heatmap cell gets: 12% of an hour below the line, as the mockup's legend says. */
private const val HEAT_FULL = 0.12

/**
 * Hour of day × channel (SPEC `HourHeatmap`): each cell the share of that hour's readings below [threshold], from
 * the empty colour to poor at [HEAT_FULL] and above. A tap on a cell gives its worst clock hour to [onHour]. Each
 * row says its worst hour to a screen reader, since the shade alone can't.
 */
@Composable
fun HourHeatmap(
    rows: List<Pair<String, List<HourCell?>>>,
    zone: ZoneId,
    onHour: (startMs: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.sightlineColors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((guide, cells) in rows) {
            val worst = cells.withIndex().filter { it.value != null }.maxByOrNull { it.value!!.belowShare }
            val described = if (worst == null || worst.value!!.belowShare == 0.0) {
                stringResource(R.string.session_heatmap_row_clean, guide)
            } else {
                stringResource(R.string.session_heatmap_row, guide, hour(worst.value!!.worstHourMs, zone), (worst.value!!.belowShare * 100).roundToInt())
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(guide, style = Numbers.inline, modifier = Modifier.width(40.dp))
                Canvas(
                    Modifier.weight(1f).height(28.dp)
                        .semantics { contentDescription = described }
                        .pointerInput(cells) {
                            detectTapGestures { tap ->
                                val i = (tap.x / size.width * 24).toInt().coerceIn(0, 23)
                                cells[i]?.let { onHour(it.worstHourMs) }
                            }
                        },
                ) {
                    val gap = 2.dp.toPx()
                    val w = (size.width - gap * 23) / 24
                    for (i in 0 until 24) {
                        val cell = cells[i]
                        val color = when (cell) {
                            null -> colors.bg
                            else -> lerp(colors.heatEmpty, colors.healthPoor, (cell.belowShare / HEAT_FULL).toFloat().coerceIn(0f, 1f))
                        }
                        drawRoundRect(color, Offset(i * (w + gap), 0f), Size(w, size.height), CornerRadius(3.dp.toPx()))
                    }
                }
            }
        }
        Row(Modifier.padding(start = 48.dp)) {
            listOf(R.string.session_hour_12am, R.string.session_hour_6am, R.string.session_hour_12pm, R.string.session_hour_6pm, R.string.session_hour_11pm)
                .forEachIndexed { i, label ->
                    Text(
                        stringResource(label),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary,
                        textAlign = when (i) {
                            0 -> androidx.compose.ui.text.style.TextAlign.Start
                            4 -> androidx.compose.ui.text.style.TextAlign.End
                            else -> androidx.compose.ui.text.style.TextAlign.Center
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
        }
        Row(Modifier.padding(start = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.session_heatmap_0), style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
            Canvas(Modifier.width(80.dp).height(8.dp)) {
                drawRoundRect(Brush.horizontalGradient(listOf(colors.heatEmpty, colors.healthPoor)), cornerRadius = CornerRadius(2.dp.toPx()))
            }
            Text(stringResource(R.string.session_heatmap_max), style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
        }
    }
}

/**
 * snq readings in 5-point bins (SPEC `Histogram`), each column coloured by its health band, with 0, 50, the good
 * line and 100 marked under it. The description gives the shares by band, which the colours alone can't.
 */
@Composable
fun Histogram(histogram: SnqHistogram, thresholds: Thresholds, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.sightlineColors
    val bands = (0 until SnqHistogram.BINS).map { thresholds.snq(histogram.lowerBound(it)) }
    val total = histogram.counts.sum().coerceAtLeast(1)
    fun share(h: Health) = (histogram.counts.indices.filter { bands[it] == h }.sumOf { histogram.counts[it] } * 100.0 / total).roundToInt()
    val described = stringResource(R.string.session_histogram_description, share(Health.GOOD), share(Health.MARGINAL), share(Health.POOR))

    val shape = RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)
    val bandColors = Health.entries.associateWith { it.color() }
    val columns = remember(bandColors) { bandColors.mapValues { (_, c) -> LineComponent(Fill(c), thickness = 10.dp, shape = shape) } }
    val provider = remember(bands, columns) {
        object : ColumnCartesianLayer.ColumnProvider {
            override fun getColumn(entry: ColumnCartesianLayerModel.Entry, seriesIndex: Int, extraStore: ExtraStore) =
                columns.getValue(bands[entry.x.toInt().coerceIn(0, bands.lastIndex)])

            override fun getWidestSeriesColumn(seriesIndex: Int, extraStore: ExtraStore) = columns.getValue(Health.GOOD)
        }
    }
    val layer = rememberColumnCartesianLayer(
        columnProvider = provider,
        rangeProvider = CartesianLayerRangeProvider.fixed(minX = 0.0, maxX = (SnqHistogram.BINS - 1).toDouble(), minY = 0.0),
    )
    val chart = rememberCartesianChart(layer, layerPadding = { CartesianLayerPadding(6.dp, 6.dp, 0.dp, 0.dp) })
    val model = remember(histogram) {
        CartesianChartModel(ColumnCartesianLayerModel.build { series((0 until SnqHistogram.BINS).toList(), histogram.counts) })
    }
    Column(modifier.semantics(mergeDescendants = true) { contentDescription = described }) {
        CartesianChartHost(
            chart = chart,
            model = model,
            modifier = Modifier.fillMaxWidth().height(96.dp),
            scrollState = rememberVicoScrollState(scrollEnabled = false),
            zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content),
        )
        BoxWithConstraints(Modifier.fillMaxWidth().height(16.dp).padding(top = 2.dp)) {
            val ticks = listOf(0, 50, thresholds.snqGood, 100).distinct()
            for (t in ticks) {
                val x = maxWidth * (t / 100f)
                Box(Modifier.offset(x = (x - 12.dp).coerceIn(0.dp, maxWidth - 24.dp)).width(24.dp), contentAlignment = when (t) {
                    0 -> Alignment.CenterStart
                    100 -> Alignment.CenterEnd
                    else -> Alignment.Center
                }) {
                    Text(t.toString(), style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
                }
            }
        }
    }
}
