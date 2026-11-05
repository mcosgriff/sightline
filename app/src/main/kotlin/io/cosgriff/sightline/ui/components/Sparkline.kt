package io.cosgriff.sightline.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** One point: when, in ms within the window, and the value; null for a reading without a lock (a gap). */
data class SparkPoint(val atMs: Long, val value: Int?)

/**
 * The last [windowMs] of one metric (SPEC `Sparkline`): a line on 0–[max], newest at the right edge at [nowMs],
 * the [threshold] dashed behind it. Readings without a lock break the line.
 */
@Composable
fun Sparkline(
    points: List<SparkPoint>,
    nowMs: Long,
    color: Color,
    thresholdColor: Color,
    description: String,
    modifier: Modifier = Modifier,
    windowMs: Long = 60_000,
    max: Int = 100,
    threshold: Int? = null,
) {
    Canvas(modifier.semantics { contentDescription = description }) {
        val pad = 2.dp.toPx()
        fun y(v: Int) = pad + (size.height - 2 * pad) * (1f - v.coerceIn(0, max).toFloat() / max)
        fun x(at: Long) = size.width * (1f - (nowMs - at).toFloat() / windowMs)
        threshold?.let {
            drawLine(
                thresholdColor, Offset(0f, y(it)), Offset(size.width, y(it)), strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
            )
        }
        val path = Path()
        var drawing = false
        for (p in points) {
            val v = p.value
            if (v == null) {
                drawing = false
                continue
            }
            if (drawing) path.lineTo(x(p.atMs), y(v)) else path.moveTo(x(p.atMs), y(v))
            drawing = true
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx(), join = StrokeJoin.Round))
    }
}
