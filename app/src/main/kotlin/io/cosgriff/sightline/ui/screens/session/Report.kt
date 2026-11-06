package io.cosgriff.sightline.ui.screens.session

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.withTranslation
import dagger.hilt.android.qualifiers.ApplicationContext
import io.cosgriff.sightline.R
import io.cosgriff.sightline.analysis.SignalEvent
import io.cosgriff.sightline.analysis.Strength
import io.cosgriff.sightline.analysis.TimelineSeries
import io.cosgriff.sightline.data.session.SessionDetail
import io.cosgriff.sightline.data.settings.Units
import io.cosgriff.sightline.ui.theme.ReportColors
import io.cosgriff.sightline.ui.theme.SightlineColors
import java.io.File
import java.time.ZoneId
import javax.inject.Inject

/** One finding as the report prints it. */
data class ReportFinding(val title: String, val badge: String?, val evidence: String, val fix: String)

/** One event as the report prints it: its time, channel and length, and what happened. */
data class ReportEvent(val title: String, val detail: String)

/**
 * Everything the report says, worded in composition with the same words as Session detail, so the page and the
 * screen never disagree.
 */
data class ReportText(
    val title: String,
    val subtitle: String,
    val device: String?,
    val figures: List<Pair<String, String>>,
    val diagnosisHeading: String,
    val findings: List<ReportFinding>,
    val timelineHeading: String,
    val legend: List<String>,
    val eventsHeading: String,
    val events: List<ReportEvent>,
    val noEvents: String,
)

@Composable
fun reportText(detail: SessionDetail, units: Units, zone: ZoneId): ReportText {
    val findings = if (detail.findings.isEmpty()) {
        noPatternText(detail).let { listOf(ReportFinding(it.title, null, it.evidence, it.fix)) }
    } else {
        detail.findings.map { f ->
            val t = f.text(units, zone)
            val badge = stringResource(if (f.strength == Strength.LIKELY) R.string.session_likely else R.string.session_possible)
            ReportFinding(t.title, badge, t.evidence, t.fix)
        }
    }
    return ReportText(
        title = title(detail),
        subtitle = subtitle(detail, zone),
        device = detail.deviceName?.let { name ->
            detail.info.firmware?.let { stringResource(R.string.session_device_firmware, name, it) } ?: name
        },
        figures = summaryFigures(detail),
        diagnosisHeading = stringResource(R.string.session_diagnosis),
        findings = findings,
        timelineHeading = stringResource(R.string.session_timeline_title),
        legend = detail.data.guideNumbers.map { stringResource(R.string.session_timeline_line, it) },
        eventsHeading = stringResource(R.string.session_events_title),
        events = detail.data.events.sortedBy { it.startMs }.map { e ->
            val w = eventWords(e, detail, units, zone)
            ReportEvent(stringResource(R.string.session_report_event, w.title, w.kind), w.detail)
        },
        noEvents = stringResource(R.string.session_events_none),
    )
}

/** What the report's chart draws: the whole session's lines and events, and the good line. */
data class ReportChart(
    val startMs: Long,
    val endMs: Long,
    val lines: List<TimelineSeries>,
    val events: List<SignalEvent>,
    val threshold: Int,
)

/**
 * The one-page summary report (SPEC Screens › Session detail › Export): a US Letter PDF with the summary figures,
 * the findings, the quality timeline and as many events as fit, in the light theme's colours. What doesn't fit is
 * counted, pointing to the events CSV.
 */
class SessionReportWriter @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val colors: SightlineColors = ReportColors
    private val inter: Typeface = ResourcesCompat.getFont(context, R.font.inter) ?: Typeface.SANS_SERIF
    private val mono: Typeface = ResourcesCompat.getFont(context, R.font.roboto_mono) ?: Typeface.MONOSPACE

    fun write(file: File, text: ReportText, chart: ReportChart, footer: String) {
        val doc = PdfDocument()
        try {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 1).create())
            draw(page.canvas, text, chart, footer)
            doc.finishPage(page)
            file.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }

    /** What made it onto the page. */
    data class Drawn(val findings: Int, val events: Int)

    /** Draws the page on [canvas], [PAGE_W] × [PAGE_H] points. */
    fun draw(canvas: Canvas, text: ReportText, chart: ReportChart, footer: String): Drawn = Page(canvas).draw(text, chart, footer)

    private inner class Page(val canvas: Canvas) {
        var y = MARGIN
        val width = PAGE_W - 2 * MARGIN

        fun draw(text: ReportText, chart: ReportChart, footer: String): Drawn {
            canvas.drawColor(Color.White.toArgb())
            paragraph(text.title, 20f, colors.textPrimary, bold = true)
            paragraph(listOfNotNull(text.subtitle, text.device).joinToString("  ·  "), 10f, colors.textSecondary)
            y += 10f
            figures(text.figures)
            y += 14f
            heading(text.diagnosisHeading)
            val footerTop = PAGE_H - MARGIN - 12f
            // The chart and the first few events stay on the page; findings take what's left above them.
            val chartBlock = HEADING_H + LEGEND_H + CHART_H + 18f + HEADING_H + EVENTS_KEPT * EVENT_H + 16f
            var findings = 0
            for (f in text.findings) {
                if (findings > 0 && y + findingHeight(f) > footerTop - chartBlock) break
                finding(f)
                findings++
            }
            y += 6f
            heading(text.timelineHeading)
            legend(text.legend)
            chart(chart)
            y += 12f
            heading(text.eventsHeading)
            if (text.events.isEmpty()) paragraph(text.noEvents, 9.5f, colors.textSecondary)
            var shown = 0
            for (e in text.events) {
                if (y + EVENT_H + 14f > footerTop) break
                event(e)
                shown++
            }
            val more = text.events.size - shown
            if (more > 0) paragraph(context.resources.getQuantityString(R.plurals.session_report_more_events, more, more), 9f, colors.textSecondary)
            y = footerTop
            paragraph(footer, 8f, colors.textSecondary)
            return Drawn(findings, shown)
        }

        fun paint(size: Float, color: Color, bold: Boolean = false, numbers: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color.toArgb()
            // The bundled fonts are variable: ask for the weight, as the app's type scale does.
            typeface = Typeface.create(if (numbers) mono else inter, if (bold) 600 else 400, false)
        }

        fun layout(text: String, paint: TextPaint, w: Float = width): StaticLayout =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, w.toInt()).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()

        fun paragraph(text: String, size: Float, color: Color, bold: Boolean = false, x: Float = MARGIN, w: Float = width) {
            val l = layout(text, paint(size, color, bold), w)
            canvas.withTranslation(x, y) { l.draw(this) }
            y += l.height + 2f
        }

        fun heading(text: String) {
            paragraph(text, 12f, colors.textPrimary, bold = true)
            y += 2f
        }

        fun figures(figures: List<Pair<String, String>>) {
            val gap = 8f
            val w = (width - gap * (figures.size - 1)) / figures.size
            val h = 50f
            val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.8f; color = colors.outline.toArgb() }
            figures.forEachIndexed { i, (label, value) ->
                val x = MARGIN + i * (w + gap)
                canvas.drawRoundRect(RectF(x, y, x + w, y + h), 8f, 8f, box)
                val l = layout(label, paint(8f, colors.textSecondary), w - 16f)
                canvas.withTranslation(x + 8f, y + 7f) { l.draw(this) }
                canvas.drawText(value, x + 8f, y + h - 9f, paint(16f, colors.textPrimary, bold = true, numbers = true))
            }
            y += h
        }

        fun findingHeight(f: ReportFinding): Float =
            layout(f.title, paint(10.5f, colors.textPrimary, bold = true), width - 70f).height +
                layout(f.evidence, paint(9f, colors.textSecondary)).height + layout(f.fix, paint(9f, colors.accent)).height + 14f

        fun finding(f: ReportFinding) {
            val top = y
            paragraph(f.title, 10.5f, colors.textPrimary, bold = true, w = width - 70f)
            if (f.badge != null) {
                val p = paint(8f, colors.textPrimary, bold = true)
                val tw = p.measureText(f.badge)
                val right = MARGIN + width
                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.outline.toArgb() }
                canvas.drawRoundRect(RectF(right - tw - 12f, top, right, top + 14f), 5f, 5f, fill)
                canvas.drawText(f.badge, right - tw - 6f, top + 10f, p)
            }
            paragraph(f.evidence, 9f, colors.textSecondary)
            paragraph(f.fix, 9f, colors.accent)
            y += 8f
        }

        fun legend(names: List<String>) {
            var x = MARGIN
            val p = paint(8.5f, colors.textSecondary)
            names.forEachIndexed { i, name ->
                val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = lineColor(i).toArgb(); strokeWidth = 2f }
                canvas.drawLine(x, y + 6f, x + 14f, y + 6f, line)
                canvas.drawText(name, x + 18f, y + 9f, p)
                x += 18f + p.measureText(name) + 14f
            }
            y += LEGEND_H
        }

        fun lineColor(i: Int): Color = if (i == 0) colors.accent else colors.chartLines[(i - 1) % colors.chartLines.size]

        fun chart(c: ReportChart) {
            val top = y
            val left = MARGIN + 18f
            val w = width - 18f
            val span = (c.endMs - c.startMs).coerceAtLeast(1).toFloat()
            fun x(ms: Long) = left + (ms - c.startMs) / span * w
            fun yOf(snq: Int) = top + CHART_H * (1 - snq.coerceIn(0, 100) / 100f)
            val axis = paint(7.5f, colors.textSecondary, numbers = true)
            val grid = Paint().apply { color = colors.outline.toArgb(); strokeWidth = 0.5f }
            for (v in listOf(0, 50, 100)) {
                canvas.drawLine(left, yOf(v), left + w, yOf(v), grid)
                canvas.drawText(v.toString(), MARGIN, yOf(v) + 3f, axis)
            }
            val band = Paint().apply { color = colors.healthPoor.copy(alpha = 0.18f).toArgb() }
            for (e in c.events.filter { it.isDip }) {
                canvas.drawRect(x(e.startMs), top, maxOf(x(e.endMs), x(e.startMs) + 1f), top + CHART_H, band)
            }
            val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colors.healthGood.toArgb()
                strokeWidth = 0.8f
                style = Paint.Style.STROKE
                pathEffect = DashPathEffect(floatArrayOf(4f, 3f), 0f)
            }
            canvas.drawLine(left, yOf(c.threshold), left + w, yOf(c.threshold), dashed)
            c.lines.forEachIndexed { i, series ->
                val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = lineColor(i).toArgb()
                    strokeWidth = 1.2f
                    style = Paint.Style.STROKE
                    strokeJoin = Paint.Join.ROUND
                }
                val path = Path()
                var last: Int? = null
                for (index in series.lows.keys.sorted()) {
                    val px = x(series.startMs + index * series.bucketMs + series.bucketMs / 2)
                    val py = yOf(series.lows.getValue(index))
                    if (last != null && index == last + 1) path.lineTo(px, py) else path.moveTo(px, py)
                    last = index
                }
                canvas.drawPath(path, stroke)
            }
            y = top + CHART_H + 6f
        }

        fun event(e: ReportEvent) {
            val split = width * 0.58f
            val title = paint(9f, colors.textPrimary)
            val detail = paint(9f, colors.textSecondary)
            canvas.drawText(TextUtils.ellipsize(e.title, title, split - 8f, TextUtils.TruncateAt.END).toString(), MARGIN, y + 9f, title)
            canvas.drawText(TextUtils.ellipsize(e.detail, detail, width - split, TextUtils.TruncateAt.END).toString(), MARGIN + split, y + 9f, detail)
            y += EVENT_H
        }
    }

    companion object {
        /** US Letter in PostScript points. */
        const val PAGE_W = 612
        const val PAGE_H = 792
        private const val MARGIN = 40f
        private const val CHART_H = 120f
        private const val EVENT_H = 13f
        private const val HEADING_H = 20f
        private const val LEGEND_H = 16f
        private const val EVENTS_KEPT = 4
    }
}
