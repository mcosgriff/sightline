package io.cosgriff.sightline.ui.screens.history

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.google.common.truth.Truth.assertThat
import io.cosgriff.sightline.analysis.Health
import io.cosgriff.sightline.data.db.SessionKind
import io.cosgriff.sightline.data.session.HistoryRow
import io.cosgriff.sightline.data.session.SessionInfo
import io.cosgriff.sightline.data.session.SessionSummary
import io.cosgriff.sightline.ui.theme.SightlineTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-rUS")
class HistoryContentTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val calls = mutableListOf<String>()
    private val actions = HistoryActions(
        onOpen = { calls += "open $it" },
        onToggle = { calls += "toggle $it" },
        onClearSelection = { calls += "clear" },
        onDelete = { calls += "delete" },
    )

    private val monitor = HistoryRow(
        SessionInfo(1, "D", SessionKind.MONITOR, START, START + 36 * HOUR, listOf("9.1", "4.1"), 0, 1_000, null),
        SessionSummary(129_600, 0.972, 23, 85, Health.GOOD, List(48) { 85 }),
    )
    private val sweep = HistoryRow(
        SessionInfo(2, "D", SessionKind.SWEEP, START - 24 * HOUR, START - 24 * HOUR + 60_000, listOf("2.1", "4.1", "9.1"), null, 1_000, null),
        null,
    )

    private fun show(state: HistoryUiState) = compose.setContent { SightlineTheme { HistoryContent(state, ZoneOffset.UTC, actions) } }

    @Test
    fun `sessions show newest first with their summaries`() {
        show(HistoryUiState(false, listOf(monitor, sweep)))
        compose.onNodeWithText("Monitor · Nov 2, 12 AM").assertExists()
        compose.onNodeWithText("36 h · 9.1 and 4.1 · 23 dips").assertExists()
        compose.onNodeWithText("Sweep · Nov 1, 12 AM").assertExists()
        compose.onNodeWithText("1 min · 3 channels · Summarising…").assertExists()
        compose.onNodeWithText("Good").assertExists()
    }

    @Test
    fun `tap opens, press and hold selects`() {
        show(HistoryUiState(false, listOf(monitor, sweep)))
        compose.onNodeWithText("Monitor · Nov 2, 12 AM").performClick()
        compose.onNodeWithText("Sweep · Nov 1, 12 AM").performTouchInput { longClick() }
        assertThat(calls).containsExactly("open 1", "toggle 2").inOrder()
    }

    @Test
    fun `while selecting, a tap toggles and delete asks first`() {
        show(HistoryUiState(false, listOf(monitor, sweep), selected = setOf(2)))
        compose.onNodeWithText("1 selected").assertExists()
        compose.onNodeWithText("Monitor · Nov 2, 12 AM").performClick()
        compose.onNode(hasContentDescription("Delete")).performClick()
        compose.onNodeWithText("Delete this session?").assertExists()
        compose.onNodeWithText("Delete").performClick()
        assertThat(calls).containsExactly("toggle 1", "delete").inOrder()
    }

    @Test
    fun `no sessions says so`() {
        show(HistoryUiState(false, emptyList()))
        compose.onNodeWithText("No sessions yet. Monitor and Sweep sessions are kept here, newest first.").assertExists()
    }

    private companion object {
        const val HOUR = 3_600_000L
        const val START = 1_793_577_600_000L
    }
}
