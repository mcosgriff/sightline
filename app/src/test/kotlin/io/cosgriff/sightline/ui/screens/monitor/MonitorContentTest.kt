package io.cosgriff.sightline.ui.screens.monitor

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import io.cosgriff.sightline.analysis.EventKind
import io.cosgriff.sightline.analysis.SignalEvent
import io.cosgriff.sightline.analysis.SignalSample
import io.cosgriff.sightline.analysis.Thresholds
import io.cosgriff.sightline.analysis.TimelineSeries
import io.cosgriff.sightline.data.device.Channel
import io.cosgriff.sightline.data.device.Device
import io.cosgriff.sightline.data.monitor.MonitorEnd
import io.cosgriff.sightline.data.monitor.MonitorPhase
import io.cosgriff.sightline.data.monitor.MonitorPlan
import io.cosgriff.sightline.data.monitor.MonitorStatus
import io.cosgriff.sightline.protocol.LostReason
import io.cosgriff.sightline.protocol.StatusParse
import io.cosgriff.sightline.protocol.StatusParser
import io.cosgriff.sightline.ui.theme.SightlineTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-rUS")
class MonitorContentTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val device = Device("D", "Living room HDHomeRun", "HDFX-4K", "1", "http://x", null, 4, "192.168.1.50", 65001, 0)
    private fun channel(guide: String) = Channel("D", guide, "Name $guide", "u", null, null, null, false)
    private val lineup = listOf(channel("2.1"), channel("9.1"))
    private val calls = mutableListOf<String>()
    private val actions = MonitorActions(
        onSelectDevice = { calls += "device $it" },
        onToggleChannel = { calls += "channel $it" },
        onRotate = { calls += "rotate $it" },
        onTuner = { calls += "tuner $it" },
        onDwell = { calls += "dwell $it" },
        onDuration = { calls += "duration $it" },
        onPoll = { calls += "poll $it" },
        onDipSnq = { calls += "dip $it" },
        onFetchWeather = { calls += "weather $it" },
        onNotifyDips = { calls += "notify $it" },
        onStart = { calls += "start" },
        onStop = { calls += "stop" },
        onDismiss = { calls += "dismiss" },
    )

    // 2026-11-04 20:48 UTC, a 12-hour plan ending 08:48.
    private val start = 1_793_825_280_000L

    private fun show(state: MonitorUiState, now: Long = start) = compose.setContent {
        SightlineTheme { MonitorContent(state, now, ZoneOffset.UTC, null, actions) }
    }

    private fun setup(vararg selected: String, rotate: Boolean = false) =
        MonitorSetup(device, listOf(device), lineup, selected.toList(), rotate = rotate, dipSnq = 60)

    @Test
    fun `setup estimates the storage and starts`() {
        show(MonitorUiState(SetupState.Ready(setup("9.1"))))
        compose.onNodeWithText("Hold one channel").assertExists()
        compose.onNodeWithText("About 43,200 readings, 3.0 MB.").performScrollTo().assertExists()
        compose.onNodeWithText("Start monitoring").performScrollTo().performClick()
        compose.onNodeWithText("2.1 Name 2.1").performScrollTo().performClick()
        compose.onNodeWithText("Rotate channels").performScrollTo().performClick()
        assertThat(calls).containsExactly("start", "channel 2.1", "rotate true").inOrder()
    }

    @Test
    fun `rotate mode asks for the time on each channel`() {
        show(MonitorUiState(SetupState.Ready(setup("9.1", "2.1", rotate = true))))
        compose.onNodeWithText("Channels, in the order picked").assertExists()
        compose.onNodeWithText("60 s").performScrollTo().performClick()
        assertThat(calls).containsExactly("dwell 60")
    }

    @Test
    fun `nothing picked can't start`() {
        show(MonitorUiState(SetupState.Ready(setup(rotate = true))))
        compose.onNodeWithText("Start monitoring").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `no device says where to pick one`() {
        show(MonitorUiState(SetupState.NoDevice))
        compose.onNodeWithText("Pick a tuner on Devices first, then set up monitoring here.").assertExists()
    }

    private fun status(
        phase: MonitorPhase = MonitorPhase.Watching(1, stream = false),
        events: List<SignalEvent> = emptyList(),
        ended: MonitorEnd? = null,
    ): MonitorStatus {
        val plan = MonitorPlan(device, listOf(channel("9.1")), null, 30_000, 12 * 3_600_000L, 1_000, Thresholds.DEFAULT, false, false)
        var series = TimelineSeries.forPlan(start, plan.durationMs, 1_000)
        for (s in 0L until 600) series += (start + s * 1_000) to SignalSample(true, 88, if (s in 100L..104L) 40 else 87, 100)
        val latest = (StatusParser.parse("ch=8vsb:515000000 lock=8vsb ss=88 snq=87 seq=100 bps=19392658 pps=0") as StatusParse.Parsed).status
        return MonitorStatus(
            plan, 1, start, channel("9.1"), phase, series,
            events = events, readings = 1_000, goodReadings = 989, latest = latest,
            ended = ended, endedAt = ended?.let { start + 2 * 3_600_000L },
        )
    }

    private val dip = SignalEvent(EventKind.DIP, "9.1", start + 100_000, start + 105_000, minSnq = 63, minSeq = 92)
    private val dropout = SignalEvent(EventKind.DROPOUT, "9.1", start + 200_000, start + 238_000, minSnq = 22, minSeq = 0, noLockMs = 4_000)

    @Test
    fun `running shows elapsed time, counters, the now line and recent dips`() {
        show(MonitorUiState(status = status(events = listOf(dip, dropout))), now = start + (6 * 60 + 12) * 60_000L)
        compose.onNodeWithText("Monitoring 9.1").assertExists()
        compose.onNodeWithText("Living room HDHomeRun · tuner 1 · 1 reading per second").assertExists()
        compose.onNodeWithText("6h 12m").assertExists()
        compose.onNodeWithText("of 12h · ends 8:48 AM").assertExists()
        compose.onNodeWithText("2").assertExists()
        compose.onNodeWithText("38 s").assertExists()
        compose.onNodeWithText("98.9%").assertExists()
        compose.onNodeWithText("Now: quality 87 · strength 88 · symbol 100").performScrollTo().assertExists()
        compose.onNodeWithText("8:51 PM · 38 s").performScrollTo().assertExists()
        compose.onNodeWithText("Lowest quality 22, lock lost 4 s").performScrollTo().assertExists()
        compose.onNodeWithText("Lowest quality 63, symbol 92").performScrollTo().assertExists()
        compose.onNodeWithText("Dropout").assertExists()
    }

    @Test
    fun `stop asks first`() {
        show(MonitorUiState(status = status()))
        compose.onNodeWithText("No dips so far.").performScrollTo().assertExists()
        compose.onNodeWithText("Stop monitoring").performClick()
        compose.onNodeWithText("Stop monitoring?").assertExists()
        compose.onNodeWithText("Keep monitoring").performClick()
        assertThat(calls).isEmpty()
        compose.onNodeWithText("Stop monitoring").performClick()
        compose.onNodeWithText("Stop").performClick()
        assertThat(calls).containsExactly("stop")
    }

    @Test
    fun `a taken tuner says who took it and that it waits`() {
        show(MonitorUiState(status = status(phase = MonitorPhase.Paused(LostReason.TakenBy("192.168.1.9"), 30.seconds))))
        compose.onNodeWithText("Paused: 192.168.1.9 took the tuner. Waiting for one to free up, trying every 30 s.").assertExists()
    }

    @Test
    fun `an ended session is summarised above the setup until dismissed`() {
        show(MonitorUiState(SetupState.Ready(setup("9.1")), status = status(events = listOf(dip), ended = MonitorEnd.STOPPED)))
        compose.onNodeWithText("Session saved").assertExists()
        compose.onNodeWithText("Stopped after 2h 00m, with 1 dip.").assertExists()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Start monitoring").performScrollTo().assertExists()
        assertThat(calls).containsExactly("dismiss")
    }
}
