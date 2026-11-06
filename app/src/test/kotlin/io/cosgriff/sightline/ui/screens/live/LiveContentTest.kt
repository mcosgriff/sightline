package io.cosgriff.sightline.ui.screens.live

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import io.cosgriff.sightline.analysis.LiveTrace
import io.cosgriff.sightline.analysis.PacketErrors
import io.cosgriff.sightline.analysis.SignalSample
import io.cosgriff.sightline.analysis.TimedSample
import io.cosgriff.sightline.data.device.Channel
import io.cosgriff.sightline.data.device.Device
import io.cosgriff.sightline.data.live.LiveState
import io.cosgriff.sightline.protocol.LostReason
import io.cosgriff.sightline.protocol.Occupancy
import io.cosgriff.sightline.protocol.StatusParse
import io.cosgriff.sightline.protocol.StatusParser
import io.cosgriff.sightline.protocol.TunedChannel
import io.cosgriff.sightline.ui.theme.SightlineTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class LiveContentTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val device = Device("1A2B3C4D", "Living room HDHomeRun", "HDFX-4K", "20260326", "http://x", null, 4, "192.168.1.50", 65001, 0)
    private val channel = Channel("1A2B3C4D", "9.1", "Station-HD", "http://x/auto/v9.1", null, null, null, false)
    private val ready = LiveSetup.Ready(device, listOf(channel, channel.copy(guideNumber = "9.2")), channel)
    private val calls = mutableListOf<String>()
    private val actions = LiveActions({ calls += "aim" }, { calls += "sweep" }, { calls += "monitor" }, { calls += "settings" }, { calls += "devices" })

    private val controls = LiveControls(
        onPrevious = { calls += "previous" },
        onNext = { calls += "next" },
        onSelect = { calls += "select $it" },
        onRetry = { calls += "retry" },
        onReload = { calls += "reload" },
        onDisconnect = { calls += "disconnect" },
        onConnect = { calls += "connect" },
    )

    private val pickChannel = SemanticsMatcher("opens the channel picker") {
        it.config.getOrElseNullable(SemanticsActions.OnClick) { null }?.label == "Pick a channel"
    }

    private fun status(line: String) = (StatusParser.parse(line) as StatusParse.Parsed).status

    private fun show(state: LiveUiState) = compose.setContent {
        SightlineTheme {
            LiveContent(state, null, controls, actions)
        }
    }

    private fun watching(line: String, best: Int = 91) = LiveState.Watching(
        channel, tuner = 1, stream = false, status = status(line),
        trace = LiveTrace() + TimedSample(0, SignalSample(true, 88, best, 100)) + TimedSample(1_000, SignalSample(true, 88, 86, 100)),
    )

    @Test
    fun `watching shows the numbers, the facts and a worded health banner`() {
        show(LiveUiState(ready, watching("ch=8vsb:599000000 lock=8vsb ss=88 snq=86 seq=100 bps=1 pps=0"), nowMs = 1_000))
        compose.onNodeWithText("Living room HDHomeRun · Tuner 1").assertExists()
        compose.onNodeWithText("9.1").assertExists()
        compose.onNodeWithText("Station-HD").assertExists()
        compose.onNode(hasContentDescription("Signal quality 86 of 100, best 91")).assertExists()
        compose.onNode(hasContentDescription("Signal strength 88 of 100")).assertExists()
        compose.onNode(hasContentDescription("Symbol quality 100 of 100")).assertExists()
        compose.onNodeWithText("Best 91").assertExists()
        compose.onNodeWithText("35").assertExists() // RF from 599 MHz
        compose.onNodeWithText("UHF").assertExists()
        compose.onNodeWithText("599 MHz").assertExists()
        compose.onNodeWithText("8VSB").assertExists()
        compose.onNodeWithText("Good").assertExists()
        compose.onNodeWithText("Clean signal, no symbol errors.").assertExists()
    }

    @Test
    fun `no packet errors row before the tuner gives its counters`() {
        show(LiveUiState(ready, watching("ch=8vsb:599000000 lock=8vsb ss=88 snq=86 seq=97"), nowMs = 1_000))
        compose.onNodeWithText("Packet errors since tuned").assertDoesNotExist()
    }

    @Test
    fun `packet errors since tuned show and say when they rise`() {
        val w = watching("ch=8vsb:599000000 lock=8vsb ss=88 snq=86 seq=97")
        show(LiveUiState(ready, w.copy(countersRead = true, errors = PacketErrors(transport = 42, crc = 7), lastAdded = PacketErrors(transport = 3)), nowMs = 1_000))
        compose.onNode(hasContentDescription("Packet errors since tuned: transport errors 42, CRC errors 7, resyncs 0, network errors 0. Rising now."))
            .performScrollTo().assertExists()
    }

    @Test
    fun `overload is explained`() {
        show(LiveUiState(ready, watching("ch=8vsb:599000000 lock=8vsb ss=98 snq=52 seq=100"), nowMs = 1_000))
        compose.onNodeWithText("Poor").assertExists()
        compose.onNodeWithText("Strong signal but low quality: likely overload or multipath.").assertExists()
    }

    @Test
    fun `no lock empties the gauges and says so`() {
        show(LiveUiState(ready, watching("ch=8vsb:599000000 lock=none ss=40 snq=0 seq=0"), nowMs = 1_000))
        compose.onNode(hasContentDescription("Signal quality, no reading")).assertExists()
        compose.onNodeWithText("No lock").assertExists()
        compose.onNodeWithText("None").assertExists()
    }

    @Test
    fun `all busy names who holds each tuner`() {
        show(
            LiveUiState(
                ready,
                LiveState.AllBusy(
                    channel,
                    mapOf(0 to Occupancy.Busy("192.168.1.20", TunedChannel.None), 2 to Occupancy.Idle),
                    30.seconds,
                ),
            ),
        )
        compose.onNodeWithText("Every tuner that can take this channel is busy").assertExists()
        compose.onNodeWithText("Tuner 0: in use by 192.168.1.20.", substring = true).assertExists()
        compose.onNodeWithText("Tuner 2 is idle but can't tune this channel.", substring = true).assertExists()
        compose.onNodeWithText("Trying again every 30 s.", substring = true).assertExists()
    }

    @Test
    fun `a lost tuner says who took it and offers to try again`() {
        show(LiveUiState(ready, LiveState.Lost(channel, 1, LostReason.TakenBy("192.168.1.77"))))
        compose.onNodeWithText("Lost tuner 1").assertExists()
        compose.onNodeWithText("192.168.1.77 is using it now.").assertExists()
        compose.onNodeWithText("Try again").performScrollTo().performClick()
        assertThat(calls).contains("retry")
    }

    @Test
    fun `channel arrows and actions`() {
        show(LiveUiState(ready, LiveState.Claiming(channel)))
        compose.onNodeWithText("Finding an idle tuner…").assertExists()
        compose.onNode(hasContentDescription("Next channel")).performClick()
        compose.onNode(hasContentDescription("Previous channel")).performClick()
        compose.onNodeWithText("Aim").performScrollTo().performClick()
        compose.onNode(hasContentDescription("Settings")).performScrollTo().performClick()
        assertThat(calls).containsExactly("next", "previous", "aim", "settings").inOrder()
    }

    @Test
    fun `the picker jumps straight to a channel found by number or name`() {
        val lineup = (1..20).map { channel.copy(guideNumber = "$it.1", guideName = "Station $it") } +
            channel.copy(guideNumber = "44.3", guideName = "Weather Now")
        show(LiveUiState(ready.copy(channels = lineup, channel = lineup[0]), LiveState.Claiming(lineup[0])))
        compose.onNode(pickChannel).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("weather")
        compose.onNodeWithText("Weather Now").performClick()
        compose.onNode(pickChannel).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("16-1")
        compose.onNodeWithText("Station 16").performClick()
        assertThat(calls).containsExactly("select 44.3", "select 16.1").inOrder()
    }

    @Test
    fun `the picker says when nothing matches`() {
        show(LiveUiState(ready, LiveState.Claiming(channel)))
        compose.onNode(pickChannel).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("zzz")
        compose.onNodeWithText("No channel matches “zzz”.").assertExists()
    }

    @Test
    fun `disconnect lets the tuner go and connect takes one again`() {
        show(LiveUiState(ready, watching("ch=8vsb:599000000 lock=8vsb ss=88 snq=86 seq=100"), nowMs = 1_000))
        compose.onNodeWithText("Disconnect").performScrollTo().performClick()
        assertThat(calls).containsExactly("disconnect")
    }

    @Test
    fun `disconnected shows no reading and offers connect`() {
        show(LiveUiState(ready, watching("ch=8vsb:599000000 lock=8vsb ss=88 snq=86 seq=100"), nowMs = 1_000, disconnected = true))
        compose.onNodeWithText("Disconnected").assertExists()
        compose.onNodeWithText("Living room HDHomeRun").assertExists() // no tuner number: none is held
        compose.onNodeWithText("Good").assertDoesNotExist()
        compose.onNodeWithText("Connect").performScrollTo().performClick()
        assertThat(calls).containsExactly("connect")
    }

    @Test
    fun `no device sends you to devices`() {
        show(LiveUiState(LiveSetup.NoDevice))
        compose.onNodeWithText("Pick a tuner on Devices to watch its signal here.").assertExists()
        compose.onNodeWithText("Go to Devices").performClick()
        assertThat(calls).containsExactly("devices")
    }
}
