package io.cosgriff.sightline.ui.screens.sweep

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import io.cosgriff.sightline.analysis.SignalSample
import io.cosgriff.sightline.analysis.SweepComparison
import io.cosgriff.sightline.analysis.SweepSummary
import io.cosgriff.sightline.analysis.SweptChannel
import io.cosgriff.sightline.analysis.Thresholds
import io.cosgriff.sightline.data.device.Channel
import io.cosgriff.sightline.data.device.Device
import io.cosgriff.sightline.data.sweep.SavedSweep
import io.cosgriff.sightline.data.sweep.SweepInfo
import io.cosgriff.sightline.data.sweep.SweepPhase
import io.cosgriff.sightline.data.sweep.SweepProgress
import io.cosgriff.sightline.protocol.Band
import io.cosgriff.sightline.ui.screens.Selection
import io.cosgriff.sightline.ui.theme.SightlineTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-rUS")
class SweepContentTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val device = Device("D", "Roof", "HDFX-4K", "20260326", "http://x", null, 4, "192.168.1.50", 65001, 0)
    private fun channel(guide: String, rf: Int?, band: Band?) = Channel("D", guide, "Name $guide", "u", rf, null, band, false)
    private val lineup = listOf(channel("2.1", 7, Band.VHF_HI), channel("9.1", 35, Band.UHF), channel("4.1", 33, Band.UHF))
    private val ready = Selection.Ready(device, lineup, lineup[0])
    private val calls = mutableListOf<String>()
    private val actions = SweepActions({ calls += "back" }, { calls += "again" }, { calls += "compare $it" }, { calls += "close" })

    private fun swept(guide: String, vararg snq: Int) =
        SweptChannel.of(guide, snq.map { SignalSample(true, 70, it, 100) }, Thresholds.DEFAULT)

    // 2026-10-28 20:41 UTC and 2026-11-04 20:41 UTC.
    private val octMs = 1_793_220_060_000L
    private val novMs = 1_793_824_860_000L

    private fun saved(id: Long, at: Long, vararg channels: SweptChannel) = SavedSweep(
        SweepInfo(id, "D", at, at + 30_000, channels.size, 6_000),
        SweepSummary(channels.toList()),
        lineup.associateBy { it.guideNumber },
    )

    private val older = saved(1, octMs, swept("2.1", 44, 46), swept("9.1", 88), swept("4.1", 80))
    private val newer = saved(2, novMs, swept("2.1", 50, 54, 58), swept("9.1", 88, 89), swept("4.1", 70, 72))

    private fun show(state: SweepUiState) = compose.setContent {
        SightlineTheme { SweepContent(state, ZoneOffset.UTC, null, actions) }
    }

    @Test
    fun `running shows the ring, the channel and what the tuner is doing`() {
        val progress = SweepProgress(1, 3, lineup[1], SweepPhase.Measuring(1, null), 0.4f)
        show(SweepUiState(ready, SweepRun.Running(progress, 3, 6_000)))
        compose.onNodeWithText("3 channels · 6 s each").assertExists()
        compose.onNodeWithText("2/3").assertExists()
        compose.onNodeWithText("9.1 Name 9.1").assertExists()
        compose.onNodeWithText("Tuner 1 · waiting for lock").assertExists()
        compose.onNodeWithText("Cancel sweep").performClick()
        assertThat(calls).containsExactly("back")
    }

    @Test
    fun `results count by health, compare with the last sweep and list worst first`() {
        val comparison = SweepComparison.of(older.summary.channels, newer.summary.channels)
        show(SweepUiState(ready, SweepRun.Done, SweepResult(newer, older, comparison)))
        compose.onNodeWithText("Sweep results").assertExists()
        compose.onNodeWithText("3 channels · 6 s each · finished 8:41 PM").assertExists()
        compose.onNodeWithText("vs sweep on Oct 28").assertExists()
        compose.onNodeWithText("1 better").assertExists()
        compose.onNodeWithText("1 worse").assertExists()
        compose.onNodeWithText("RF 7 · VHF-hi").assertExists()
        compose.onNode(hasContentDescription("2.1: signal quality 50 to 58, median 54")).assertExists()
        compose.onNodeWithText("The tick marks the threshold, 80. Poor counts channels with no lock too.").performScrollTo().assertExists()
        compose.onNodeWithText("Compare sweeps").performClick()
        compose.onNodeWithText("Sweep again").performClick()
        assertThat(calls).containsExactly("compare null", "again").inOrder()
    }

    @Test
    fun `compare lists each channel's change with an arrow and a signed number`() {
        val comparison = SweepComparison.of(older.summary.channels, newer.summary.channels)
        show(
            SweepUiState(
                ready, SweepRun.Done, SweepResult(newer, older, comparison),
                compare = SweepCompare(older, comparison),
                sweeps = listOf(newer.info, older.info),
            ),
        )
        compose.onNodeWithText("This sweep vs Oct 28, 8:41 PM").assertExists()
        compose.onNodeWithText("+10").assertExists() // 2.1: 44 → 54 (lower medians)
        compose.onNodeWithText("−10").assertExists() // 4.1: 80 → 70
        compose.onNodeWithText("0").assertExists() // 9.1: 88 → 88
        compose.onNodeWithText("Compare with another sweep").performClick()
        compose.onNodeWithText("Oct 28, 8:41 PM").performClick()
        assertThat(calls).containsExactly("compare 1")
    }

    @Test
    fun `a device that stopped answering offers another go`() {
        show(SweepUiState(ready, SweepRun.Failed))
        compose.onNodeWithText("The device stopped answering, so the sweep stopped. Nothing was saved.").assertExists()
        compose.onNodeWithText("Sweep again").performClick()
        assertThat(calls).containsExactly("again")
    }
}
