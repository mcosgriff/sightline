package io.cosgriff.sightline.ui.screens.monitor

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import io.cosgriff.sightline.analysis.EventKind
import io.cosgriff.sightline.analysis.PacketErrors
import io.cosgriff.sightline.analysis.SignalEvent
import io.cosgriff.sightline.analysis.SignalSample
import io.cosgriff.sightline.analysis.Thresholds
import io.cosgriff.sightline.analysis.TimelineSeries
import io.cosgriff.sightline.data.device.Channel
import io.cosgriff.sightline.data.device.Device
import io.cosgriff.sightline.data.device.TunerHolder
import io.cosgriff.sightline.data.monitor.ComparedUnit
import io.cosgriff.sightline.data.monitor.MonitorEnd
import io.cosgriff.sightline.data.monitor.MonitorPhase
import io.cosgriff.sightline.data.monitor.MonitorPlan
import io.cosgriff.sightline.data.monitor.MonitorStatus
import io.cosgriff.sightline.data.monitor.UnitState
import io.cosgriff.sightline.data.monitor.WatchedTuner
import io.cosgriff.sightline.data.monitor.WatchedUse
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
        onWatch = { calls += "watch $it" },
        onWholeDevice = { calls += "whole $it" },
        onAllDevices = { calls += "all devices $it" },
        onCompare = { calls += "compare $it" },
        onCompareWith = { calls += "compare with $it" },
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
    fun `running shows the session's packet errors once the tuner gives its counters`() {
        val counted = status().copy(countersRead = true, errors = PacketErrors(transport = 412, crc = 103, resyncs = 2))
        show(MonitorUiState(status = counted), now = start + 60_000L)
        compose.onNode(hasContentDescription("Packet errors this session: transport errors 412, CRC errors 103, resyncs 2, network errors 0. Damaged on the way in from the antenna."))
            .performScrollTo().assertExists()
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

    @Test
    fun `watch mode picks a tuner by who is using it, not a channel`() {
        val holders = listOf(TunerHolder.Idle, TunerHolder.ThisApp, TunerHolder.Other("192.168.1.20"), TunerHolder.Idle)
        show(MonitorUiState(SetupState.Ready(setup("9.1").copy(watch = true, holders = holders))))
        compose.onNodeWithText("Tuner to watch").assertExists()
        compose.onNodeWithText("Channel").assertDoesNotExist()
        compose.onNodeWithText("Start monitoring").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("2 · 192.168.1.20").performScrollTo().performClick()
        compose.onNodeWithText("1 · this app").assertExists()
        compose.onNodeWithText("Hold one channel").performScrollTo().performClick()
        assertThat(calls).containsExactly("tuner 2", "rotate false").inOrder()
    }

    @Test
    fun `watching says who is using the tuner, and when nobody is`() {
        val plan = MonitorPlan(device, lineup, 2, 30_000, null, 1_000, Thresholds.DEFAULT, false, false, watch = true)
        val base = status().copy(plan = plan, phase = MonitorPhase.Observing(2, "192.168.1.20", "http://192.168.1.20:5004"))
        show(MonitorUiState(SetupState.Loading, status = base))
        compose.onNodeWithText("Watching tuner 2 · 9.1").assertExists()
        compose.onNodeWithText("In use by 192.168.1.20. Sightline is only reading it.").assertExists()
    }

    @Test
    fun `watching an idle tuner waits for someone`() {
        val plan = MonitorPlan(device, lineup, 2, 30_000, null, 1_000, Thresholds.DEFAULT, false, false, watch = true)
        show(MonitorUiState(SetupState.Loading, status = status().copy(plan = plan, phase = MonitorPhase.ObservingIdle(2))))
        compose.onNodeWithText("Nobody is using tuner 2. Recording starts when someone tunes it.").assertExists()
    }

    private val garage = Device("E", "Garage HDHomeRun", "HDFX-4K", "1", "http://y", null, 4, "192.168.1.51", 65001, 0)

    @Test
    fun `a watch of every tuner picks neither tuner nor channel, offers both devices, and says the most it stores`() {
        val s = setup("9.1").copy(devices = listOf(device, garage), wholeDevice = true)
        show(MonitorUiState(SetupState.Ready(s)))
        compose.onNodeWithText("Channel").assertDoesNotExist()
        compose.onNodeWithText("Tuner").assertDoesNotExist()
        compose.onNodeWithText("Tuner to watch").assertDoesNotExist()
        compose.onNodeWithText("Living room HDHomeRun only").performScrollTo().assertExists()
        // Four tuners a second for 12 hours.
        compose.onNodeWithText("At most 172,800 readings, 12 MB, if every tuner is in use the whole time.").performScrollTo().assertExists()
        compose.onNodeWithText("Both devices").performScrollTo().performClick()
        compose.onNodeWithText("Start monitoring").performScrollTo().performClick()
        compose.onNodeWithText("Watch every tuner").performScrollTo().performClick()
        assertThat(calls).containsExactly("all devices true", "start", "whole true").inOrder()
    }

    @Test
    fun `both devices double the most it stores`() {
        show(MonitorUiState(SetupState.Ready(setup("9.1").copy(devices = listOf(device, garage), wholeDevice = true, allDevices = true))))
        compose.onNodeWithText("At most 345,600 readings, 24 MB, if every tuner is in use the whole time.").performScrollTo().assertExists()
    }

    @Test
    fun `a watch of every tuner shows one row per tuner, by device, with who holds it, its channel and health`() {
        val plan = MonitorPlan(device, lineup, null, 0, null, 1_000, Thresholds.DEFAULT, false, false, wholeDevices = listOf(device, garage))
        val locked = (StatusParser.parse("ch=8vsb:515000000 lock=8vsb ss=88 snq=87 seq=100 bps=19392658 pps=0") as StatusParse.Parsed).status
        val tuners = listOf(
            WatchedTuner("D", device.friendlyName, 0, WatchedUse.Idle),
            WatchedTuner("D", device.friendlyName, 1, WatchedUse.InUse("192.0.2.20", channel("9.1"), locked)),
            WatchedTuner("D", device.friendlyName, 2, WatchedUse.InUse(null, channel("2.1"), null)),
            WatchedTuner("D", device.friendlyName, 3, WatchedUse.Idle),
            WatchedTuner("E", garage.friendlyName, 0, WatchedUse.NoAnswer),
            WatchedTuner("E", garage.friendlyName, 1, WatchedUse.Idle),
            WatchedTuner("E", garage.friendlyName, 2, WatchedUse.Idle),
            WatchedTuner("E", garage.friendlyName, 3, WatchedUse.Idle),
        )
        val tunerDip = dip.copy(deviceId = "D", tuner = 1)
        val running = status(events = listOf(tunerDip)).copy(plan = plan, phase = MonitorPhase.WatchingAll(), tuners = tuners)
        show(MonitorUiState(SetupState.Loading, status = running), now = start + 3_600_000L)
        compose.onNodeWithText("Watching every tuner on 2 devices").assertExists()
        compose.onNodeWithText("8 tuners · 1 reading per second").assertExists()
        compose.onNodeWithText("2 of 8 tuners in use. Sightline is only reading them.").assertExists()
        compose.onNodeWithText("Garage HDHomeRun").performScrollTo().assertExists()
        compose.onNodeWithText("9.1 Name 9.1 · 192.0.2.20").performScrollTo().assertExists()
        compose.onNodeWithText("Quality 87").performScrollTo().assertExists()
        compose.onNodeWithText("Good").performScrollTo().assertExists()
        compose.onNodeWithText("2.1 Name 2.1 · another client").performScrollTo().assertExists()
        compose.onNodeWithText("Waiting for lock").performScrollTo().assertExists()
        compose.onNodeWithText("No answer").performScrollTo().assertExists()
        compose.onAllNodesWithText("Idle: nobody is using it").assertCountEquals(5)
        // Each dip names its tuner.
        compose.onNodeWithText("8:49 PM · Living room HDHomeRun · tuner 1 · 5 s").performScrollTo().assertExists()
        // No single timeline: the rows take its place.
        compose.onNodeWithText("Signal quality, so far").assertDoesNotExist()
    }

    @Test
    fun `a device that stops answering in a watch of every tuner is said on its own`() {
        val plan = MonitorPlan(device, lineup, null, 0, null, 1_000, Thresholds.DEFAULT, false, false, wholeDevices = listOf(device, garage))
        val tuners = listOf(WatchedTuner("E", garage.friendlyName, 0, WatchedUse.NoAnswer))
        show(MonitorUiState(SetupState.Loading, status = status().copy(plan = plan, phase = MonitorPhase.WatchingAll(setOf("E")), tuners = tuners)))
        compose.onNodeWithText("Garage HDHomeRun isn't answering. The other tuners are still read.").assertExists()
    }

    @Test
    fun `compare is only offered with a second device`() {
        show(MonitorUiState(SetupState.Ready(setup("9.1"))))
        compose.onNodeWithText("Compare two units").assertDoesNotExist()
    }

    @Test
    fun `compare picks the second unit and a channel both carry, no tuner, and counts both units' readings`() {
        val theirs = listOf(channel("9.1").copy(deviceId = "E"), channel("14.1").copy(deviceId = "E"))
        val s = setup("9.1").copy(devices = listOf(device, garage), compare = true, otherId = "E", otherChannels = theirs)
        show(MonitorUiState(SetupState.Ready(s)))
        compose.onNodeWithText("Compare with").assertExists()
        compose.onNodeWithText("Garage HDHomeRun").assertExists()
        compose.onNodeWithText("Channel, on both").assertExists()
        // Only the channel both lineups carry.
        compose.onNodeWithText("9.1 Name 9.1").performScrollTo().assertExists()
        compose.onNodeWithText("2.1 Name 2.1").assertDoesNotExist()
        compose.onNodeWithText("Tuner").assertDoesNotExist()
        compose.onNodeWithText("About 86,400 readings, 6.0 MB, from both units.").performScrollTo().assertExists()
        compose.onNodeWithText("Start monitoring").performScrollTo().performClick()
        compose.onNodeWithText("Compare two units").performScrollTo().performClick()
        assertThat(calls).containsExactly("start", "compare true").inOrder()
    }

    @Test
    fun `compare with no channel in common says so and can't start`() {
        val theirs = listOf(channel("14.1").copy(deviceId = "E"))
        val s = setup().copy(devices = listOf(device, garage), compare = true, otherId = "E", otherChannels = theirs)
        show(MonitorUiState(SetupState.Ready(s)))
        compose.onNodeWithText("These two units carry no channel in common.").performScrollTo().assertExists()
        compose.onNodeWithText("Start monitoring").performScrollTo().assertIsNotEnabled()
    }

    private fun comparePlan() = MonitorPlan(
        device, listOf(channel("9.1"), channel("9.1").copy(deviceId = "E")), null, 0, 12 * 3_600_000L, 1_000, Thresholds.DEFAULT, false, false,
        compareUnits = listOf(device, garage),
    )

    @Test
    fun `comparing shows a row per unit with its tuner and health, both lines on one timeline, and dips by unit`() {
        val locked = (StatusParser.parse("ch=8vsb:515000000 lock=8vsb ss=88 snq=87 seq=100 bps=19392658 pps=0") as StatusParse.Parsed).status
        val line = TimelineSeries.forPlan(start, 12 * 3_600_000L, 1_000)
        val units = listOf(
            ComparedUnit("D", device.friendlyName, line, UnitState.Holding(3, stream = false), locked),
            ComparedUnit("E", garage.friendlyName, line, UnitState.NoSpare(30.seconds)),
        )
        val unitDip = dropout.copy(deviceId = "E", tuner = 2)
        val running = status(events = listOf(unitDip)).copy(plan = comparePlan(), phase = MonitorPhase.Comparing, units = units)
        show(MonitorUiState(SetupState.Loading, status = running), now = start + 3_600_000L)
        compose.onNodeWithText("Comparing 9.1 on two units").assertExists()
        compose.onNodeWithText("Living room HDHomeRun and Garage HDHomeRun · 1 reading per second").assertExists()
        compose.onNodeWithText("Tuner 3").assertExists()
        compose.onNodeWithText("Quality 87").assertExists()
        compose.onNodeWithText("Good").assertExists()
        compose.onNodeWithText("Waiting: no tuner to spare, as one stays idle for recordings. Trying again within 30 s.").assertExists()
        compose.onNodeWithText("Signal quality, so far").performScrollTo().assertExists()
        compose.onNodeWithText("8:51 PM · Garage HDHomeRun · tuner 2 · 38 s").performScrollTo().assertExists()
    }

    @Test
    fun `a compare a unit can't spare a tuner for didn't start, and says which unit`() {
        val line = TimelineSeries.forPlan(start, 12 * 3_600_000L, 1_000)
        val refused = status(ended = MonitorEnd.NO_SPARE_TUNER).copy(
            plan = comparePlan(), phase = MonitorPhase.Comparing,
            units = listOf(ComparedUnit("D", device.friendlyName, line), ComparedUnit("E", garage.friendlyName, line, UnitState.NoSpare(30.seconds))),
        )
        show(MonitorUiState(SetupState.Ready(setup("9.1")), status = refused))
        compose.onNodeWithText("Didn't start").assertExists()
        compose.onNodeWithText(
            "Garage HDHomeRun has no idle tuner to spare. Sightline keeps one tuner free on each unit for recordings, so it needs two idle there.",
        ).assertExists()
        compose.onNodeWithText("Open session").assertDoesNotExist()
        compose.onNodeWithText("Session saved").assertDoesNotExist()
    }
}
