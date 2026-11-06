package io.cosgriff.sightline.ui.screens.diagnostics

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import io.cosgriff.sightline.data.device.Device
import io.cosgriff.sightline.data.device.Diagnostics
import io.cosgriff.sightline.data.device.TunerHolder
import io.cosgriff.sightline.protocol.ControlResult
import io.cosgriff.sightline.protocol.DeviceInfo
import io.cosgriff.sightline.protocol.DeviceSnapshot
import io.cosgriff.sightline.protocol.Probe
import io.cosgriff.sightline.protocol.TunerSnapshot
import io.cosgriff.sightline.protocol.UnreachableReason
import io.cosgriff.sightline.ui.theme.SightlineTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class DiagnosticsContentTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val device = Device("1A2B3C4D", "Living room HDHomeRun", "HDFX-4K", "20260326", "http://x", null, 4, "192.168.1.18", 65001, 0)
    private val calls = mutableListOf<String>()

    private fun v(s: String) = ControlResult.Value(s)

    private fun tuner(n: Int, status: String, debug: String, lockkey: String = "none") = TunerSnapshot(
        n,
        listOf(
            Probe("/tuner$n/status", v(status)),
            Probe("/tuner$n/debug", v(debug)),
            Probe("/tuner$n/lockkey", v(lockkey)),
            Probe("/tuner$n/target", v("none")),
        ),
    )

    private val idleDebug = "tun: ch=none lock=none ss=0 snq=0 seq=0 dbg=0\ndev: bps=0 resync=0 overflow=0\n" +
        "ts:  bps=0 te=0 crc=0\nnet: bps=0 pps=0 err=0 stop=0\n"
    private val badDebug = "tun: ch=8vsb:485000000 lock=8vsb:485000000 ss=81 snq=63 seq=97 dbg=0\n" +
        "dev: bps=19636224 resync=2 overflow=0\nts:  bps=8335168 te=412 crc=103\nnet: bps=0 pps=0 err=0 stop=0\n"

    private fun diagnostics(upgrade: String? = null, unreachable: Boolean = false): Diagnostics {
        val system = DeviceSnapshot.SYSTEM.map {
            Probe(it, if (unreachable) ControlResult.Unreachable(UnreachableReason.TIMED_OUT) else v(if (it == "/sys/version") "20260326" else "x"))
        }
        val tuners = if (unreachable) {
            (0..1).map { TunerSnapshot(it, emptyList()) }
        } else {
            listOf(
                tuner(0, "ch=none lock=none ss=0 snq=0 seq=0 bps=0 pps=0", idleDebug),
                tuner(1, "ch=8vsb:485000000 lock=8vsb ss=81 snq=63 seq=97 bps=1 pps=0", badDebug, lockkey = "192.168.1.40"),
            )
        }
        val holders = if (unreachable) listOf(TunerHolder.Unknown, TunerHolder.Unknown) else listOf(TunerHolder.Idle, TunerHolder.ThisPhone(staleKey = true))
        val info = DeviceInfo("HDHomeRun FLEX 4K", "HDFX-4K", "hdhomerun_dvr_atsc3", "20260326", "1A2B3C4D", 4, upgradeAvailable = upgrade)
        return Diagnostics(device, DeviceSnapshot(system, tuners, "192.168.1.40"), info.takeUnless { unreachable }, holders, setOf(1), 0)
    }

    private fun show(state: DiagnosticsState) = compose.setContent {
        SightlineTheme {
            DiagnosticsContent(state, ZoneOffset.UTC, onBack = { calls += "back" }, onRelease = { calls += "release $it" }, onShare = { calls += "share" })
        }
    }

    @Test
    fun `shows firmware, holders and counters, and releases a left-behind key`() {
        show(DiagnosticsState.Read(diagnostics()))
        compose.onNodeWithText("20260326").assertExists()
        compose.onNodeWithText("None offered by the tuner").assertExists()
        compose.onNodeWithText("Idle").assertExists()
        compose.onNodeWithText("Held by this phone").performScrollTo().assertExists()
        compose.onNodeWithText("412").performScrollTo().assertExists() // transport errors
        compose.onNodeWithText("103").assertExists() // crc
        compose.onNodeWithText("8.3 Mbps").assertExists()
        compose.onNodeWithText("Release").performScrollTo().performClick()
        compose.onNode(hasContentDescription("Share diagnostics")).performClick()
        assertThat(calls).containsExactly("release 1", "share").inOrder()
    }

    @Test
    fun `an offered update is named`() {
        show(DiagnosticsState.Read(diagnostics(upgrade = "20261020")))
        compose.onNodeWithText("The tuner offers 20261020. Install it from the HDHomeRun app.").assertExists()
    }

    @Test
    fun `raw replies open on request`() {
        show(DiagnosticsState.Read(diagnostics()))
        compose.onAllNodesWithText("Show raw replies")[1].performScrollTo().performClick()
        compose.onNodeWithText("/tuner1/debug").performScrollTo().assertExists()
    }

    @Test
    fun `a silent tuner says so`() {
        show(DiagnosticsState.Read(diagnostics(unreachable = true)))
        compose.onNodeWithText("The tuner isn't answering. Is it on, and on this network?").assertExists()
        compose.onNodeWithText("Unknown: discover.json didn't answer").assertExists()
    }
}
