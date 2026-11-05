package io.cosgriff.sightline.ui.screens.devices

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onAllNodesWithText
import com.google.common.truth.Truth.assertThat
import io.cosgriff.sightline.data.device.Device
import io.cosgriff.sightline.data.device.TunerUse
import io.cosgriff.sightline.ui.theme.SightlineTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class DevicesContentTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val living = Device("1A2B3C4D", "Living room HDHomeRun", "HDFX-4K", "20260326", "http://192.168.1.50", null, 4, "192.168.1.50", 65001, 0)
    private val opened = mutableListOf<String>()
    private val added = mutableListOf<String>()

    private fun show(state: DevicesUiState, network: Boolean = true) = compose.setContent {
        SightlineTheme {
            DevicesContent(
                state = state,
                nowMs = 10 * 60_000L,
                networkCard = if (network) null else ({ androidx.compose.material3.Text("NETWORK CARD") }),
                onRescan = {},
                onAdd = { added += it },
                onOpen = { opened += it },
                onOpenProtocolDebug = null,
            )
        }
    }

    @Test
    fun `a card shows the device, each tuner in words, and who holds a busy one`() {
        show(
            DevicesUiState(
                cards = listOf(DeviceCard(living, true, listOf(TunerUse.Idle, TunerUse.Sightline, TunerUse.Other("192.168.1.20"), TunerUse.Unknown))),
                scannedAt = 10 * 60_000L,
            ),
        )
        compose.onNodeWithText("Living room HDHomeRun").assertIsDisplayed()
        compose.onNodeWithText("HDFX-4K · firmware 20260326").assertExists()
        compose.onNodeWithText("192.168.1.50 · ID 1A2B3C4D").assertExists()
        compose.onNodeWithText("Online").assertExists()
        compose.onNodeWithText("1 found on Wi-Fi · scanned just now").assertExists()
        // Colour is never the only signal: each tuner says what it is.
        compose.onNode(hasContentDescription("Tuner 0, Idle")).assertExists()
        compose.onNode(hasContentDescription("Tuner 1, Sightline")).assertExists()
        compose.onNode(hasContentDescription("Tuner 2, In use")).assertExists()
        compose.onNode(hasContentDescription("Tuner 3, No answer")).assertExists()
        compose.onNodeWithText("Tuner 2 is in use by 192.168.1.20. Sightline won't touch it.").assertExists()

        compose.onNodeWithText("Living room HDHomeRun").performClick()
        assertThat(opened).containsExactly("1A2B3C4D")
    }

    @Test
    fun `scanned minutes ago`() {
        show(DevicesUiState(cards = listOf(DeviceCard(living, null, null)), scannedAt = 7 * 60_000L))
        compose.onNodeWithText("1 found on Wi-Fi · scanned 3 min ago").assertExists()
    }

    @Test
    fun `nothing found explains the same network and offers retry and manual entry`() {
        show(DevicesUiState(scannedAt = 10 * 60_000L))
        compose.onNodeWithText("No tuners found").assertExists()
        compose.onNodeWithText("Retry").assertExists()
        compose.onNodeWithText("Not listed? Add by address").assertExists()
    }

    @Test
    fun `adding by address`() {
        show(DevicesUiState(scannedAt = 0))
        compose.onNode(hasText("hdhomerun.local or 192.168.1.x")).performTextInput("192.168.1.18")
        compose.onNodeWithText("Add").performClick()
        assertThat(added).containsExactly("192.168.1.18")
    }

    @Test
    fun `a failed add says why`() {
        show(DevicesUiState(scannedAt = 0, addFailure = AddFailure.NOT_FOUND))
        compose.onNodeWithText("No HDHomeRun answered at that address.").assertExists()
    }

    @Test
    fun `without the network permission the card asks first`() {
        show(DevicesUiState(), network = false)
        compose.onNodeWithText("NETWORK CARD").assertExists()
        assertThat(compose.onAllNodesWithText("No tuners found").fetchSemanticsNodes()).isEmpty()
    }
}
