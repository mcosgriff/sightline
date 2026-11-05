package io.cosgriff.sightline.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModel
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.LineCartesianLayerModel
import com.patrykandpatrick.vico.compose.cartesian.decoration.Decoration
import com.patrykandpatrick.vico.compose.cartesian.layer.CartesianLayerPadding
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.Fill
import io.cosgriff.sightline.analysis.SignalEvent
import io.cosgriff.sightline.analysis.TimelineSeries
import io.cosgriff.sightline.ui.theme.Numbers
import io.cosgriff.sightline.ui.theme.sightlineColors

/**
 * snq over a session (SPEC `SignalTimeline`): the lowest snq per stretch of time as a line, dips as red bands and
 * outages (no answer, gaps) as grey ones, [threshold] dashed. With a planned [endMs] the chart spans the whole
 * plan and the part not yet run is shaded and labelled [stillToCome]; otherwise it ends at [nowMs]. [timeLabels]
 * sit evenly under it, first at the start and last at the end. [description] is what a screen reader says.
 *
 * Zoom, the range selector and the weather panel come with Session detail (milestone 5).
 */
@Composable
fun SignalTimeline(
    series: TimelineSeries,
    events: List<SignalEvent>,
    nowMs: Long,
    endMs: Long?,
    threshold: Int,
    timeLabels: List<String>,
    stillToCome: String?,
    description: String,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 120.dp,
) {
    val colors = MaterialTheme.sightlineColors
    val lastX = series.lows.keys.maxOrNull()?.toDouble() ?: 0.0
    val nowX = series.position(nowMs).coerceAtLeast(lastX)
    val maxX = maxOf(endMs?.let { series.position(it) } ?: nowX, nowX, 1.0)
    val nowFraction = (nowX / maxX).toFloat().coerceIn(0f, 1f)

    val bands = events.map { e ->
        TimelineDecoration.Band(series.position(e.startMs), series.position(e.endMs).coerceAtLeast(series.position(e.startMs) + 0.5), e.isDip)
    }
    val decoration = TimelineDecoration(
        bands = bands,
        threshold = threshold,
        futureFrom = if (endMs != null && nowX < maxX) nowX else null,
        lastPoint = series.lows.maxByOrNull { it.key }?.let { (x, y) -> x.toDouble() to y },
        dipColor = colors.healthPoor.copy(alpha = 0.35f),
        outageColor = colors.healthNoLock.copy(alpha = 0.35f),
        thresholdColor = colors.textSecondary.copy(alpha = 0.6f),
        futureColor = colors.bg.copy(alpha = 0.55f),
        pointColor = colors.accent,
    )
    val line = LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(Fill(colors.accent)),
        areaFill = null,
    )
    val layer = rememberLineCartesianLayer(
        lineProvider = LineCartesianLayer.LineProvider.series(line),
        rangeProvider = CartesianLayerRangeProvider.fixed(minX = 0.0, maxX = maxX, minY = 0.0, maxY = 100.0),
    )
    val chart = rememberCartesianChart(
        layer,
        layerPadding = { CartesianLayerPadding(0.dp, 0.dp, 0.dp, 0.dp) },
        decorations = listOf(decoration),
    )
    val model = remember(series) {
        val points = series.lows.toSortedMap()
        // Vico needs at least one point; before the first reading the chart is the threshold and the plan.
        val xs = points.keys.ifEmpty { listOf(0) }.toList()
        val ys = points.values.ifEmpty { listOf(0) }.toList()
        CartesianChartModel(LineCartesianLayerModel.build { series(xs, ys) })
    }

    Column(modifier.semantics(mergeDescendants = true) { contentDescription = description }) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(chartHeight)) {
            if (series.lows.isNotEmpty()) {
                CartesianChartHost(
                    chart = chart,
                    model = model,
                    modifier = Modifier.fillMaxWidth().height(chartHeight),
                    scrollState = rememberVicoScrollState(scrollEnabled = false),
                    zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content),
                )
            }
            Text(
                threshold.toString(),
                style = Numbers.inline,
                color = colors.textSecondary,
                modifier = Modifier.align(Alignment.TopEnd).offset(y = chartHeight * ((100 - threshold) / 100f) - 18.dp).padding(end = 4.dp),
            )
            if (stillToCome != null && endMs != null && nowFraction < 0.85f) {
                val start = maxWidth * nowFraction
                Box(Modifier.offset(x = start).width(maxWidth - start).height(chartHeight), contentAlignment = Alignment.Center) {
                    Text(stillToCome, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary, textAlign = TextAlign.Center)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            timeLabels.forEachIndexed { i, label ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary,
                    textAlign = when (i) {
                        0 -> TextAlign.Start
                        timeLabels.lastIndex -> TextAlign.End
                        else -> TextAlign.Center
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** What Vico can't draw by itself: event bands, the threshold, the part of the plan still to come, and "now". */
private class TimelineDecoration(
    val bands: List<Band>,
    val threshold: Int,
    val futureFrom: Double?,
    val lastPoint: Pair<Double, Int>?,
    val dipColor: Color,
    val outageColor: Color,
    val thresholdColor: Color,
    val futureColor: Color,
    val pointColor: Color,
) : Decoration {
    data class Band(val from: Double, val to: Double, val dip: Boolean)

    private fun CartesianDrawingContext.x(value: Double): Float {
        val r = ranges
        return layerBounds.left + layerDimensions.startPadding - scroll +
            ((value - r.minX) / r.xStep).toFloat() * layerDimensions.xSpacing
    }

    private fun CartesianDrawingContext.y(value: Number): Float =
        layerBounds.bottom - layerBounds.height * value.toFloat() / 100f

    override fun drawUnderLayers(context: CartesianDrawingContext) = with(context) {
        val paint = Paint()
        for (b in bands) {
            paint.color = if (b.dip) dipColor else outageColor
            canvas.drawRect(Rect(x(b.from), layerBounds.top, x(b.to), layerBounds.bottom), paint)
        }
        futureFrom?.let {
            paint.color = futureColor
            canvas.drawRect(Rect(x(it), layerBounds.top, layerBounds.right, layerBounds.bottom), paint)
        }
        val dash = Paint().apply {
            color = thresholdColor
            strokeWidth = density.density
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4 * density.density, 4 * density.density))
        }
        val ty = y(threshold)
        canvas.drawLine(Offset(layerBounds.left, ty), Offset(layerBounds.right, ty), dash)
    }

    override fun drawOverLayers(context: CartesianDrawingContext) = with(context) {
        val (px, py) = lastPoint ?: return@with
        canvas.drawCircle(Offset(x(px), y(py)), 4 * density.density, Paint().apply { color = pointColor })
    }
}
