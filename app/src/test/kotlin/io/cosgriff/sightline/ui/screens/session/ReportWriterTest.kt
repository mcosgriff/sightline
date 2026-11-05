package io.cosgriff.sightline.ui.screens.session

import android.app.Application
import com.google.common.truth.Truth.assertThat
import io.cosgriff.sightline.analysis.EventKind
import io.cosgriff.sightline.analysis.SignalEvent
import io.cosgriff.sightline.analysis.TimelineSeries
import android.graphics.Bitmap
import android.graphics.Canvas
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The report keeps to one page, whatever the number of findings and events: what doesn't fit is left off. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReportWriterTest {
    @Test
    fun `writes one page even with more than fits`() {
        val text = ReportText(
            title = "Monitor session",
            subtitle = "Nov 2, 12 AM – Nov 2, 6 AM · 6 h · 9.1",
            device = "Roof",
            figures = listOf("Time in good health" to "97%", "Dips" to "23", "Longest dropout" to "1m 04s", "Median quality" to "85"),
            diagnosisHeading = "Diagnosis",
            findings = List(12) { ReportFinding("Finding $it", "Likely", "Evidence ".repeat(20), "Fix it") },
            timelineHeading = "Signal quality",
            legend = listOf("9.1 quality"),
            eventsHeading = "Events",
            events = List(200) { ReportEvent("Nov 3, 2:15 PM · 9.1 · 6 s · Dip", "Lowest quality 58") },
            noEvents = "No events.",
        )
        val series = TimelineSeries(0, 60_000, lows = (0 until 360).associateWith { 80 + it % 10 })
        val chart = ReportChart(0, 6 * 3_600_000L, listOf(series), listOf(SignalEvent(EventKind.DIP, "9.1", 600_000, 780_000, 40, 70, 0)), 80)
        // Robolectric has no PdfDocument, so the page is drawn on a bitmap of the same size.
        val bitmap = Bitmap.createBitmap(SessionReportWriter.PAGE_W, SessionReportWriter.PAGE_H, Bitmap.Config.ARGB_8888)
        val drawn = SessionReportWriter(RuntimeEnvironment.getApplication()).draw(Canvas(bitmap), text, chart, "Made with Sightline 0.1.0.")
        assertThat(drawn.findings).isIn(1..11)
        assertThat(drawn.events).isIn(1..199)
    }
}
