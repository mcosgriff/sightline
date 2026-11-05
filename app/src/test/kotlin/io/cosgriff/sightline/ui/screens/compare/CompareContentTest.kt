package io.cosgriff.sightline.ui.screens.compare

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import io.cosgriff.sightline.analysis.Diagnosis
import io.cosgriff.sightline.analysis.LoggedReading
import io.cosgriff.sightline.analysis.SessionData
import io.cosgriff.sightline.analysis.SessionStats
import io.cosgriff.sightline.analysis.SignalSample
import io.cosgriff.sightline.analysis.SweepComparison
import io.cosgriff.sightline.analysis.Thresholds
import io.cosgriff.sightline.data.db.SessionKind
import io.cosgriff.sightline.data.session.SessionDetail
import io.cosgriff.sightline.data.session.SessionInfo
import io.cosgriff.sightline.data.session.SessionWeather
import io.cosgriff.sightline.ui.theme.SightlineTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-rUS")
class CompareContentTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** [hours] of 9.1 and 4.1 a reading a minute, at [snq9] and [snq4]. */
    private fun session(id: Long, start: Long, hours: Int, snq9: Int, snq4: Int): SessionDetail {
        val readings = (0 until hours * 60).flatMap { m ->
            listOf(
                LoggedReading(start + m * MINUTE, "9.1", 0, SignalSample(true, 80, snq9, 100)),
                LoggedReading(start + m * MINUTE, "4.1", 1, SignalSample(true, 80, snq4, 100)),
            )
        }
        val data = SessionData(start, start + hours * HOUR, MINUTE, readings, emptyList(), zone = ZoneOffset.UTC)
        return SessionDetail(
            SessionInfo(id, "D", SessionKind.MONITOR, start, start + hours * HOUR, listOf("9.1", "4.1"), null, MINUTE, null),
            "Roof", data, SessionStats.of(data, Thresholds.DEFAULT), Diagnosis.run(data, Thresholds.DEFAULT), emptyMap(), SessionWeather.NotAsked,
        )
    }

    @Test
    fun `figures side by side, and each channel's change`() {
        val before = session(1, START, 2, 70, 88)
        val after = session(2, START + 48 * HOUR, 6, 86, 87)
        val calls = mutableListOf<String>()
        compose.setContent {
            SightlineTheme {
                CompareContent(CompareUiState(false, before, after, SweepComparison.of(before.stats.channels, after.stats.channels)), ZoneOffset.UTC, { calls += "back" })
            }
        }
        compose.onNodeWithText("Nov 2, 12 AM vs Nov 4, 12 AM").assertExists()
        compose.onNodeWithText("Length").assertExists()
        compose.onNodeWithText("2 h").assertExists()
        compose.onNodeWithText("6 h").assertExists()
        compose.onNodeWithText("Dips per hour").assertExists()
        compose.onNodeWithText("+16").assertExists()
        compose.onNode(hasContentDescription("Back")).performClick()
        assertThat(calls).containsExactly("back")
    }

    @Test
    fun `a gone session says so`() {
        compose.setContent { SightlineTheme { CompareContent(CompareUiState(false, null, null, null), ZoneOffset.UTC, {}) } }
        compose.onNodeWithText("One of these sessions is gone.").assertExists()
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 3_600_000L
        const val START = 1_793_577_600_000L
    }
}
