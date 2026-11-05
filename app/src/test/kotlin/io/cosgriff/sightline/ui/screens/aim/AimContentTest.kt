package io.cosgriff.sightline.ui.screens.aim

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onAllNodesWithText
import com.google.common.truth.Truth.assertThat
import io.cosgriff.sightline.analysis.AimTrace
import io.cosgriff.sightline.analysis.SignalSample
import io.cosgriff.sightline.data.device.Channel
import io.cosgriff.sightline.data.device.Device
import io.cosgriff.sightline.data.heading.CompassHeading
import io.cosgriff.sightline.data.live.LiveState
import io.cosgriff.sightline.data.settings.Settings
import io.cosgriff.sightline.protocol.StatusParse
import io.cosgriff.sightline.protocol.StatusParser
import io.cosgriff.sightline.ui.screens.Selection
import io.cosgriff.sightline.ui.theme.SightlineTheme
import io.cosgriff.sightline.ui.theme.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AimContentTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val device = Device("1A2B3C4D", "Roof", "HDFX-4K", "20260326", "http://x", null, 4, "192.168.1.50", 65001, 0)
    private val channel = Channel("1A2B3C4D", "9.1", "Station-HD", "http://x/auto/v9.1", null, null, null, false)
    private val other = channel.copy(guideNumber = "4.1", guideName = "Four")
    private val ready = Selection.Ready(device, listOf(channel, other), channel)
    private val calls = mutableListOf<String>()
    private val actions = AimActions(
        onBack = { calls += "back" },
        onTone = { calls += "tone $it" },
        onHaptics = { calls += "haptics $it" },
        onResetPeak = { calls += "reset" },
        onRetry = { calls += "retry" },
        onSecond = { calls += "second ${it?.guideNumber}" },
        onTowers = { calls += "towers $it" },
        onTrueNorth = { calls += "true north" },
    )

    private fun status(line: String) = (StatusParser.parse(line) as StatusParse.Parsed).status

    private fun show(state: AimUiState, settings: Settings = Settings()) = compose.setContent {
        SightlineTheme(ThemeMode.TrueBlack) { AimContent(state, settings, null, actions) }
    }

    private val reading = status("ch=8vsb:599000000 lock=8vsb ss=80 snq=88 seq=100 bps=1 pps=0")
    private val trace = AimTrace()
        .add(SignalSample(true, 80, 85, 100), 240.0).first
        .add(SignalSample(true, 80, 91, 100), 247.0).first

    @Test
    fun `the giant number, its health in words, the peak with its heading and the heading now`() {
        show(AimUiState(ready, LiveState.Watching(channel, 1, false, reading), trace, CompassHeading(252.4, trueNorth = false)))
        compose.onNodeWithText("Aim · 9.1 Station-HD").assertExists()
        compose.onNodeWithText("RF 35 UHF · tuner 1 · hold the phone to the mast").assertExists()
        compose.onNode(hasContentDescription("Signal quality 88, Good")).assertExists()
        compose.onNodeWithText("Good").assertExists()
        compose.onNodeWithText("Best 91 at 247°").assertExists()
        compose.onNodeWithText("· now 252°").assertExists()
        compose.onNode(hasContentDescription("Symbol quality 100, Good")).assertExists()
        compose.onNode(hasContentDescription("Polar plot of signal quality by compass heading. Best: 91, at 247°.")).performScrollTo().assertExists()
        compose.onNodeWithText("Magnetic north · use true north").performScrollTo().performClick()
        assertThat(calls).containsExactly("true north")
    }

    @Test
    fun `no lock shows a dash and says so`() {
        show(AimUiState(ready, LiveState.Watching(channel, 1, false, status("ch=8vsb:599000000 lock=none ss=30 snq=0 seq=0"))))
        compose.onNode(hasContentDescription("No lock")).assertExists()
        compose.onNodeWithText("No peak yet").assertExists()
    }

    @Test
    fun `without a compass the plot says why it is empty`() {
        show(AimUiState(ready, LiveState.Watching(channel, 0, false, reading), compass = false))
        compose.onNode(hasContentDescription("Polar plot, empty: this phone has no compass")).performScrollTo().assertExists()
        compose.onNodeWithText("This phone has no compass, so nothing is plotted.").performScrollTo().assertExists()
    }

    @Test
    fun `toggles and reset call back`() {
        show(AimUiState(ready, LiveState.Watching(channel, 0, false, reading)), Settings(aimTone = false, aimHaptics = true))
        compose.onNodeWithText("Tone off").performClick()
        compose.onNodeWithText("Haptics on").performClick()
        compose.onNodeWithText("Reset peak").performClick()
        assertThat(calls).containsExactly("tone true", "haptics false", "reset").inOrder()
    }

    @Test
    fun `a second channel is picked from the lineup`() {
        show(AimUiState(ready, LiveState.Watching(channel, 0, false, reading)))
        compose.onNodeWithText("Watch a second channel on another tuner").performScrollTo().performClick()
        compose.onNodeWithText("4.1 Four").performClick()
        assertThat(calls).containsExactly("second 4.1")
    }

    @Test
    fun `the second channel's reading is worded`() {
        val second = SecondTuner(other, LiveState.Watching(other, 2, false, status("ch=8vsb:189000000 lock=8vsb ss=70 snq=72 seq=100")))
        show(AimUiState(ready, LiveState.Watching(channel, 0, false, reading), second = second))
        compose.onNodeWithText("Second tuner · 4.1").performScrollTo().assertExists()
        compose.onNodeWithText("72 Marginal").assertExists()
    }

    @Test
    fun `tower bearings are typed in and listed`() {
        show(AimUiState(ready, LiveState.Watching(channel, 0, false, reading)), Settings(towerBearings = listOf(245)))
        compose.onNodeWithText("Tower A 245°").performScrollTo().assertExists()
        compose.onNodeWithText("Edit towers").performScrollTo().performClick()
        compose.onAllNodesWithText("Tower B")[0].performTextInput("120")
        compose.onNodeWithText("Save").performClick()
        assertThat(calls).containsExactly("towers [245, 120]")
    }

    @Test
    fun `all busy is explained`() {
        show(AimUiState(ready, LiveState.AllBusy(channel, emptyMap(), kotlin.time.Duration.parse("30s"))))
        compose.onNodeWithText("Every tuner is in use by another client. Trying again within 30 s.").assertExists()
    }
}
