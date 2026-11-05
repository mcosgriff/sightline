package io.cosgriff.sightline.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** The arc runs 270°, from bottom left (135°, clockwise from three o'clock) round to bottom right. */
private const val START = 135f
private const val SWEEP = 270f

/**
 * One metric, 0–[max], as a 270° arc (SPEC `ArcGauge`): a track, a fill in the health colour up to the value,
 * a tick across the arc at [threshold], and a hollow ghost marker at the session [peak]. The fill moves with a
 * spring (medium stiffness, no overshoot). [value] null draws the track only. The number and label go in
 * [content], centred; [description] is what TalkBack reads, since the drawing says nothing to it.
 */
@Composable
fun ArcGauge(
    value: Int?,
    color: Color,
    trackColor: Color,
    markColor: Color,
    holeColor: Color,
    stroke: Dp,
    description: String,
    modifier: Modifier = Modifier,
    max: Int = 100,
    threshold: Int? = null,
    peak: Int? = null,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val fraction by animateFloatAsState(
        targetValue = ((value ?: 0).coerceIn(0, max)).toFloat() / max,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "gauge",
    )
    Box(modifier.semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = stroke.toPx()
            val radius = min(size.width, size.height * 1.17f) / 2f - w / 2f
            val center = Offset(size.width / 2f, radius + w / 2f)
            val topLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2, radius * 2)
            fun point(fraction: Float, r: Float): Offset {
                val a = Math.toRadians((START + SWEEP * fraction).toDouble())
                return Offset(center.x + r * cos(a).toFloat(), center.y + r * sin(a).toFloat())
            }

            drawArc(trackColor, START, SWEEP, false, topLeft, arcSize, style = Stroke(w, cap = StrokeCap.Round))
            if (value != null && fraction > 0f) {
                drawArc(color, START, SWEEP * fraction, false, topLeft, arcSize, style = Stroke(w, cap = StrokeCap.Round))
            }
            threshold?.let {
                val f = it.toFloat() / max
                drawLine(markColor, point(f, radius - w * 0.8f), point(f, radius + w * 0.8f), strokeWidth = w / 6f)
            }
            peak?.let {
                val p = point(it.toFloat() / max, radius)
                drawCircle(holeColor, radius = w * 0.4f, center = p)
                drawCircle(markColor, radius = w * 0.4f, center = p, style = Stroke(w / 6f))
            }
        }
        content()
    }
}
