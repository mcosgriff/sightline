package io.cosgriff.sightline.ui.screens.multituner

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import io.cosgriff.sightline.analysis.Thresholds
import io.cosgriff.sightline.data.device.Channel
import io.cosgriff.sightline.data.device.Device
import io.cosgriff.sightline.data.device.TunerUse
import io.cosgriff.sightline.data.live.LiveState
import io.cosgriff.sightline.protocol.Occupancy
import io.cosgriff.sightline.protocol.StatusParse
import io.cosgriff.sightline.protocol.StatusParser
import io.cosgriff.sightline.ui.screens.Selection
import io.cosgriff.sightline.ui.theme.SightlineTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-rUS")
class MultiTunerContentTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val device = Device("1A2B3C4D", "Living room HDHomeRun", "HDFX-4K", "20260326", "http://x", null, 4, "192.168.1.50", 65001, 0)
    private val ch9 = Channel(device.deviceId, "9.1", "KUSA-HD", "", 9, null, null, false)
    private val ch4 = Channel(device.deviceId, "4.1", "KCNC-HD", "", 23, null, null, false)
    private val ready = Selection.Ready(device, listOf(ch4, ch9), ch9)

    private val calls = mutableListOf<String>()
    private val actions = MultiTunerActions(
        onBack = { calls += "back" },
        onTune = { t, c -> calls += "tune $t ${c.guideNumber}" },
        onStop = { calls += "stop $it" },
        onRetry = { calls += "retry $it" },
    )

    private fun status(line: String) = (StatusParser.parse(line) as StatusParse.Parsed).status

    private fun show(cells: List<TunerCell>) = compose.setContent {
        SightlineTheme { MultiTunerContent(MultiTunerUiState(ready, cells, Thresholds.DEFAULT), null, actions) }
    }

    private val grid = listOf(
        TunerCell.On(0, ch9, LiveState.Watching(ch9, 0, false, status("ch=8vsb:189000000 lock=8vsb ss=88 snq=86 seq=100 bps=1 pps=0"))),
        TunerCell.On(1, ch4, LiveState.AllBusy(ch4, mapOf(1 to Occupancy.Idle), 30.seconds)),
        TunerCell.Off(2, TunerUse.Other("192.168.1.20")),
        TunerCell.Off(3, TunerUse.Idle, spare = true),
    )

    @Test
    fun `each tuner shows its channel and health, or who holds it`() {
        show(grid)
        compose.onNodeWithText("Tuner 0").assertExists()
        compose.onNodeWithText("9.1 KUSA-HD").assertExists()
        compose.onNode(hasContentDescription("Signal quality on tuner 0: 86")).assertExists()
        compose.onNodeWithText("SS 88 · SEQ 100").assertExists()
        compose.onNodeWithText("Good").assertExists()
        compose.onNodeWithText("Idle, but it can't tune this channel.\nTrying again every 30 s.").assertExists()
        compose.onNodeWithText("In use by 192.168.1.20.").assertExists()
        compose.onNodeWithText("Kept free for recordings. Tap to use it anyway.").assertExists()
    }

    @Test
    fun `a tap on a running tuner offers stop and the channels`() {
        show(grid)
        compose.onNodeWithText("9.1 KUSA-HD").performClick()
        compose.onNodeWithText("Stop this tuner").performClick()
        compose.onNodeWithText("Kept free for recordings. Tap to use it anyway.").performClick()
        // The menu's item, not tuner 1's cell, which shows the same channel.
        compose.onNode(hasText("4.1 KCNC-HD") and hasAnyAncestor(isPopup())).performClick()
        assertThat(calls).containsExactly("stop 0", "tune 3 4.1").inOrder()
    }

    @Test
    fun `a tuner another client holds offers nothing`() {
        show(grid)
        compose.onNodeWithText("In use by 192.168.1.20.").performClick()
        compose.onNodeWithText("Stop this tuner").assertDoesNotExist()
        assertThat(calls).isEmpty()
    }

    @Test
    fun `a lost tuner retries on tap`() {
        show(listOf(TunerCell.On(0, ch9, LiveState.Lost(ch9, 0, io.cosgriff.sightline.protocol.LostReason.Released))))
        compose.onNodeWithText("Another client took it or changed its channel. Tap to try again.").performClick()
        assertThat(calls).containsExactly("retry 0")
    }
}
