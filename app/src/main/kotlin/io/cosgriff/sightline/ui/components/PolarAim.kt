package io.cosgriff.sightline.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import io.cosgriff.sightline.R
import io.cosgriff.sightline.analysis.AimPeak
import io.cosgriff.sightline.analysis.AimPoint
import io.cosgriff.sightline.ui.theme.Inter
import io.cosgriff.sightline.ui.theme.sightlineColors
import kotlin.math.cos
import kotlin.math.sin

/**
 * snq against compass heading while the antenna turns (SPEC `PolarAim`). North is up and headings run clockwise.
 * The radius is linear in snq with rings at 80 and 100, as in the mockup; below 20 sits at the centre. The best
 * heading is a dashed line and a dot, the phone's heading a solid needle, tower bearings short ticks outside the
 * ring. Points more than [maxGapDeg] apart aren't joined, so a stretch never turned through stays empty.
 */
@Composable
fun PolarAim(
    points: List<AimPoint>,
    headingDeg: Double?,
    best: AimPeak?,
    towers: List<Int>,
    description: String,
    modifier: Modifier = Modifier,
    maxGapDeg: Double = 15.0,
) {
    val colors = MaterialTheme.sightlineColors
    val measurer = rememberTextMeasurer()
    val label = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = colors.textSecondary)
    val small = TextStyle(fontFamily = Inter, fontSize = 10.sp, color = colors.healthNoLock)
    val cardinals = listOf(
        0.0 to stringResource(R.string.compass_n),
        90.0 to stringResource(R.string.compass_e),
        180.0 to stringResource(R.string.compass_s),
        270.0 to stringResource(R.string.compass_w),
    )
    Canvas(modifier.semantics { contentDescription = description }) {
        val c = center
        val outer = size.minDimension / 2f - 20.dp.toPx()
        fun r(snq: Int) = outer * ((snq - 20).coerceIn(0, 100) / 100f)
        fun at(deg: Double, radius: Float): Offset {
            val rad = Math.toRadians(deg)
            return Offset(c.x + radius * sin(rad).toFloat(), c.y - radius * cos(rad).toFloat())
        }
        val hair = 1.dp.toPx()

        // Frame, the 100 ring dashed, the 80 ring, the cross.
        drawCircle(colors.outline, outer, c, style = Stroke(hair))
        drawCircle(colors.outline, r(100), c, style = Stroke(hair, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx()))))
        drawCircle(colors.outline.copy(alpha = 0.6f), r(80), c, style = Stroke(hair))
        drawLine(colors.outline.copy(alpha = 0.6f), at(0.0, outer), at(180.0, outer), hair)
        drawLine(colors.outline.copy(alpha = 0.6f), at(90.0, outer), at(270.0, outer), hair)
        for ((deg, text) in cardinals) centred(measurer.measure(text, label), at(deg, outer + 12.dp.toPx()))
        for (ring in listOf(100, 80)) {
            val layout = measurer.measure(ring.toString(), small)
            drawText(layout, topLeft = Offset(c.x + 4.dp.toPx(), c.y - r(ring) - layout.size.height / 2f))
        }

        // Tower bearings: short thick ticks just outside the frame.
        for (bearing in towers) {
            drawLine(colors.healthMarginal, at(bearing.toDouble(), outer + 1.dp.toPx()), at(bearing.toDouble(), outer + 12.dp.toPx()), 3.dp.toPx())
        }

        // The best heading, dashed from the centre to the frame.
        best?.headingDeg?.let { deg ->
            drawLine(
                colors.healthGood, c, at(deg.toDouble(), outer), 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
            )
        }

        // The trace: joined where neighbouring, filled from the centre, a dot per bin.
        if (points.isNotEmpty()) {
            val runs = mutableListOf(mutableListOf(points.first()))
            for (p in points.drop(1)) {
                if (p.headingDeg - runs.last().last().headingDeg <= maxGapDeg) runs.last() += p else runs += mutableListOf(p)
            }
            // Join across north when the first and last runs meet there.
            if (runs.size > 1 && points.first().headingDeg + 360 - points.last().headingDeg <= maxGapDeg) {
                runs.last() += runs.removeAt(0)
            }
            for (run in runs) {
                val line = Path()
                run.forEachIndexed { i, p ->
                    val o = at(p.headingDeg, r(p.snq))
                    if (i == 0) line.moveTo(o.x, o.y) else line.lineTo(o.x, o.y)
                }
                if (run.size > 1) {
                    val fill = Path().apply {
                        moveTo(c.x, c.y)
                        run.forEach { p -> at(p.headingDeg, r(p.snq)).let { lineTo(it.x, it.y) } }
                        close()
                    }
                    drawPath(fill, colors.accent.copy(alpha = 0.14f))
                    drawPath(line, colors.accent, style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
                }
                run.forEach { p -> drawCircle(colors.accent, 3.dp.toPx(), at(p.headingDeg, r(p.snq))) }
            }
        }
        best?.headingDeg?.let { deg ->
            val o = at(deg.toDouble(), r(best.snq))
            drawCircle(colors.bg, 8.dp.toPx(), o)
            drawCircle(colors.healthGood, 6.dp.toPx(), o)
        }

        // The phone's heading.
        headingDeg?.let { deg ->
            drawLine(colors.textPrimary, c, at(deg, outer * 0.86f), 2.5.dp.toPx(), cap = StrokeCap.Round)
        }
        drawCircle(colors.textPrimary, 4.dp.toPx(), c)
    }
}

private fun DrawScope.centred(layout: androidx.compose.ui.text.TextLayoutResult, at: Offset) {
    drawText(layout, topLeft = Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f))
}
