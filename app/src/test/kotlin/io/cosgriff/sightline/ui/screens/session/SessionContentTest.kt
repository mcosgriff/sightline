package io.cosgriff.sightline.ui.screens.session

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onFirst
import com.google.common.truth.Truth.assertThat
import io.cosgriff.sightline.analysis.Diagnosis
import io.cosgriff.sightline.analysis.DipDetector
import io.cosgriff.sightline.analysis.LoggedReading
import io.cosgriff.sightline.analysis.SessionData
import io.cosgriff.sightline.analysis.SessionStats
import io.cosgriff.sightline.analysis.SignalSample
import io.cosgriff.sightline.analysis.Thresholds
import io.cosgriff.sightline.analysis.WeatherHour
import io.cosgriff.sightline.analysis.timelineOf
import io.cosgriff.sightline.data.db.SessionKind
import io.cosgriff.sightline.data.session.SessionDetail
import io.cosgriff.sightline.data.session.SessionInfo
import io.cosgriff.sightline.data.session.SessionWeather
import io.cosgriff.sightline.data.settings.Units
import io.cosgriff.sightline.ui.theme.SightlineTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-rUS")
class SessionContentTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val calls = mutableListOf<String>()
    private val actions = SessionActions(
        onBack = { calls += "back" },
        onZoom = { s, e -> calls += "zoom ${(s - START) / MINUTE}..${(e - START) / MINUTE}" },
        onZoomOut = { calls += "zoom out" },
        onWeatherMetric = { calls += "metric $it" },
        onRetryWeather = { calls += "retry" },
        onUseLocation = { calls += "location" },
        onExportCsv = { calls += "csv" },
        onShareReport = { text, footer -> report = text; calls += "report $footer" },
    )
    private var report: ReportText? = null

    /**
     * Six hours on 9.1 a reading a minute: clean, except in hours 2 and 3, gusting to 45 km/h, when it dips for
     * 3 minutes every 10. The shape of the Demo wind channel, without the Demo tuner (debug builds only).
     */
    private fun windy(weather: SessionWeather = gusts()): SessionDetail {
        val readings = (0 until 6 * 60).map { m ->
            val bad = m / 60 in 2..3 && m % 10 < 3
            LoggedReading(START + m * MINUTE, "9.1", 0, SignalSample(true, 80, if (bad) 40 else 88, if (bad) 70 else 100))
        }
        val detector = DipDetector(Thresholds.DEFAULT)
        val events = readings.flatMap { detector.reading(it.atMs, it.guideNumber, it.sample) } + detector.finish(readings.last().atMs)
        val data = SessionData(
            START, START + 6 * HOUR, MINUTE, readings, events,
            weather = (weather as? SessionWeather.Fetched)?.hours.orEmpty(), zone = ZoneOffset.UTC,
        )
        return SessionDetail(
            SessionInfo(7, "D", SessionKind.MONITOR, START, START + 6 * HOUR, listOf("9.1"), 0, MINUTE, null),
            "Roof", data, SessionStats.of(data, Thresholds.DEFAULT), Diagnosis.run(data, Thresholds.DEFAULT), emptyMap(), weather,
        )
    }

    private fun gusts() = SessionWeather.Fetched((0 until 6).map { WeatherHour(START + it * HOUR, 10.0, 10.0, if (it in 2..3) 45.0 else 8.0, 0.0) }, demo = false)

    private fun show(detail: SessionDetail?, loading: Boolean = false) = compose.setContent {
        val window = detail?.let { TimeWindow(it.data.startMs, it.data.endMs) }
        val lines = detail?.data?.guideNumbers?.map { timelineOf(detail.data.byChannel.getValue(it), window!!.startMs, window.endMs, 360) }.orEmpty()
        SightlineTheme { SessionContent(SessionUiState(loading, detail, window, lines), Thresholds.DEFAULT, Units.IMPERIAL, ZoneOffset.UTC, actions) }
    }

    @Test
    fun `a windy session shows dips follow the wind, likely, with the numbers and the fix`() {
        show(windy())
        compose.onNodeWithText("Monitor session").assertExists()
        compose.onNodeWithText("Nov 2, 12 AM – Nov 2, 6 AM · 6 h · 9.1").assertExists()
        compose.onNodeWithText("Dips follow the wind").assertExists()
        compose.onAllNodesWithText("Likely").onFirst().assertExists()
        compose.onNodeWithText("12 dips with gusts over 19 mph, none in calm hours. 12 of 12 dips fell on Nov 2 between 2 AM and 4 AM.").assertExists()
        compose.onNodeWithText("Fix: secure the mast and antenna; check outdoor connectors.").assertExists()
    }

    @Test
    fun `summary cards`() {
        show(windy())
        compose.onNodeWithText("Time in good health").assertExists()
        compose.onNodeWithText("90.0%").assertExists()
        compose.onNodeWithText("12").assertExists()
        compose.onNodeWithText("None").assertExists() // no dropout
        compose.onNodeWithText("88").assertExists()
    }

    @Test
    fun `without weather nothing follows the wind, and a clean session finds no pattern`() {
        val clean = windy(SessionWeather.NotAsked).let { d ->
            val readings = d.data.readings.map { it.copy(sample = SignalSample(true, 80, 88, 100)) }
            val data = d.data.copy(readings = readings, events = emptyList())
            d.copy(data = data, stats = SessionStats.of(data, Thresholds.DEFAULT), findings = Diagnosis.run(data, Thresholds.DEFAULT))
        }
        show(clean)
        compose.onNodeWithText("No pattern found").assertExists()
        compose.onNodeWithText("Dips follow the wind").assertDoesNotExist()
    }

    @Test
    fun `tapping an event zooms the timeline to it`() {
        show(windy())
        // Newest first: the last dip starts at 3:50 AM and lasts 3 minutes.
        scrollTo("Nov 2, 3:50 AM · 9.1 · 3m 00s")
        compose.onNodeWithText("Nov 2, 3:50 AM · 9.1 · 3m 00s").performClick()
        assertThat(calls).contains("zoom ${230 - 9}..${233 + 9}")
    }

    @Test
    fun `weather without a home offers to use the location`() {
        show(windy(SessionWeather.NoHome))
        scrollTo("Use my location")
        compose.onNodeWithText("Use my location").performClick()
        assertThat(calls).contains("location")
    }

    @Test
    fun `failed weather offers a retry`() {
        show(windy(SessionWeather.Missing))
        scrollTo("Try again")
        compose.onNodeWithText("Try again").performClick()
        assertThat(calls).contains("retry")
    }

    @Test
    fun `demo weather says so`() {
        show(windy(gusts().copy(demo = true)))
        scrollTo("Demo weather, made up to match the Demo wind channel.")
    }

    @Test
    fun `a gone session says so and goes back`() {
        show(null)
        compose.onNodeWithText("This session is gone.").assertExists()
        compose.onNodeWithContentDescriptionBack()
        assertThat(calls).containsExactly("back")
    }

    @Test
    fun `export CSV and share report sit at the end, the report worded like the page`() {
        show(windy())
        scrollTo("Share report")
        compose.onNodeWithText("Export CSV").performClick()
        compose.onNodeWithText("Share report").performClick()
        compose.waitForIdle()
        assertThat(calls.first()).isEqualTo("csv")
        assertThat(calls.last()).startsWith("report Made with Sightline ")
        val r = report!!
        assertThat(r.title).isEqualTo("Monitor session")
        assertThat(r.device).isEqualTo("Roof")
        assertThat(r.figures.map { it.first }).containsExactly("Time in good health", "Dips", "Longest dropout", "Median quality").inOrder()
        assertThat(r.findings.first().title).isEqualTo("Dips follow the wind")
        assertThat(r.findings.first().badge).isEqualTo("Likely")
        assertThat(r.events).isNotEmpty()
        assertThat(r.events.first().title).endsWith(" · Dip")
    }

    @Test
    fun `the header's export button offers CSV or the report`() {
        show(windy())
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Export")).performClick()
        compose.onNodeWithText("Readings and events (CSV)").performClick()
        assertThat(calls).containsExactly("csv")
    }

    /** Session detail is a lazy list: what's below the fold isn't composed until scrolled to. */
    private fun scrollTo(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onNodeWithContentDescriptionBack() =
        onNode(androidx.compose.ui.test.hasContentDescription("Back")).performClick()

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 3_600_000L
        const val START = 1_793_577_600_000L
    }
}
