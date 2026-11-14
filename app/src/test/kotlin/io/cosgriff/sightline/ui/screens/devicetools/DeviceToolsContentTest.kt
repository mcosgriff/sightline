package io.cosgriff.sightline.ui.screens.devicetools

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import io.cosgriff.sightline.data.device.Device
import io.cosgriff.sightline.data.device.ToolsBlock
import io.cosgriff.sightline.data.device.UpdateFailure
import io.cosgriff.sightline.data.device.UpdateStep
import io.cosgriff.sightline.protocol.LineupStatus
import io.cosgriff.sightline.ui.theme.SightlineTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class DeviceToolsContentTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val device = Device("1A2B3C4D", "Living room HDHomeRun", "HDFX-4K", "20260326", "http://x", null, 4, "192.168.1.18", 65001, 0)
    private val idle = LineupStatus(scanInProgress = 0, scanPossible = 1, source = "Antenna", sourceList = listOf("Antenna", "Cable"))
    private val calls = mutableListOf<String>()

    private fun state(
        block: ToolsBlock? = null,
        lineup: LineupStatus? = idle,
        latest: LatestCheck = LatestCheck.NotChecked,
        update: UpdateStep? = null,
    ) = DeviceToolsState(
        device = device, block = block, lineup = lineup, channelCount = 7, source = "Antenna",
        installed = "20260326", firmwareName = "hdhomerun_dvr_atsc3", latest = latest, update = update, loaded = true,
    )

    private val actions = DeviceToolsActions(
        onScan = { calls += "scan" },
        onStopScan = { calls += "stop" },
        onCheck = { calls += "check" },
        onInstall = { calls += "install" },
        onPickSource = { calls += "source $it" },
    )

    private fun show(state: DeviceToolsState) = compose.setContent { SightlineTheme { DeviceToolsContent(state, actions) } }

    @Test
    fun `a tuner in use names its client and keeps both tools off`() {
        show(state(block = ToolsBlock.InUse(mapOf(2 to "192.168.1.21")), latest = LatestCheck.Found("20261107")))
        compose.onNodeWithText("Tuner 2 is in use by 192.168.1.21. Sightline won't touch it.").assertExists()
        compose.onNodeWithText("Scan channels").assertIsNotEnabled()
        compose.onNodeWithText("Install 20261107").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `a scan is confirmed before it starts`() {
        show(state())
        compose.onNodeWithText("Lineup now: 7 channels").assertExists()
        compose.onNodeWithText("Cable").performClick()
        compose.onNodeWithText("Scan channels").assertIsEnabled().performClick()
        assertThat(calls).containsExactly("source Cable")
        compose.onNodeWithText("Scan for channels?").assertExists()
        compose.onNodeWithText("Scan").performClick()
        assertThat(calls).containsExactly("source Cable", "scan").inOrder()
    }

    @Test
    fun `a running scan shows progress and can be stopped`() {
        show(state(lineup = LineupStatus(scanInProgress = 1, progress = 34, found = 12)))
        compose.onNodeWithText("Scanning: 34% · 12 programs found").assertExists()
        compose.onNodeWithText("Stop scan").performClick()
        assertThat(calls).containsExactly("stop")
    }

    @Test
    fun `siliconDust is asked only on request`() {
        show(state())
        compose.onNodeWithText("Not checked").assertExists()
        compose.onNodeWithText("Check SiliconDust").performScrollTo().performClick()
        assertThat(calls).containsExactly("check")
    }

    @Test
    fun `install is confirmed before anything is sent`() {
        show(state(latest = LatestCheck.Found("20261107")))
        compose.onNodeWithText("20261107, newer").assertExists()
        compose.onNodeWithText("Install 20261107").performScrollTo().performClick()
        assertThat(calls).isEmpty()
        compose.onNodeWithText("Install firmware 20261107?").assertExists()
        compose.onNodeWithText("Install").performClick()
        assertThat(calls).containsExactly("install")
    }

    @Test
    fun `a restart in progress is worded`() {
        show(state(update = UpdateStep.Restarting("20261107")))
        compose.onNodeWithText("The tuner is installing 20261107 and restarting. Don't unplug it.").performScrollTo().assertExists()
    }

    @Test
    fun `a refused file is worded with the tuner's message`() {
        show(state(update = UpdateStep.Failed("20261107", UpdateFailure.REJECTED, "ERROR: invalid image")))
        compose.onNodeWithText("The tuner refused the file (ERROR: invalid image). It keeps its firmware.").performScrollTo().assertExists()
    }
}
