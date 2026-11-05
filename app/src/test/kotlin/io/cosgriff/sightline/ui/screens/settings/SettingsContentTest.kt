package io.cosgriff.sightline.ui.screens.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import io.cosgriff.sightline.analysis.Thresholds
import io.cosgriff.sightline.analysis.ToneRange
import io.cosgriff.sightline.data.settings.Home
import io.cosgriff.sightline.data.settings.Settings
import io.cosgriff.sightline.data.settings.ThemeSetting
import io.cosgriff.sightline.data.settings.Units
import io.cosgriff.sightline.ui.theme.SightlineTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-rUS")
class SettingsContentTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val calls = mutableListOf<Any?>()
    private val actions = SettingsActions(
        onBack = { calls += "back" },
        onTheme = { calls += it },
        onUnits = { calls += it },
        onThresholds = { calls += it },
        onResetThresholds = { calls += "reset" },
        onPollInterval = { calls += "poll $it" },
        onKeepScreenOn = { calls += "screen $it" },
        onToneRange = { calls += it },
        onRetention = { calls += "keep $it" },
        onUseLocation = { calls += "locate" },
        onForgetHome = { calls += "forget" },
    )

    private fun show(settings: Settings?) = compose.setContent { SightlineTheme { SettingsContent(settings, "0.1.0", actions) } }

    @Test
    fun `the current choices are selected`() {
        show(Settings())
        compose.onNodeWithText("Dark").assertIsSelected()
        compose.onNodeWithText("°F and mph").assertIsSelected()
        compose.onNodeWithText("Every second").performScrollTo().assertIsSelected()
        compose.onNodeWithText("90 days").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Not set.").performScrollTo().assertExists()
        compose.onNodeWithText("Sightline 0.1.0").performScrollTo().assertExists()
    }

    @Test
    fun `choices write the setting`() {
        show(Settings(keepScreenOn = false))
        compose.onNodeWithText("True black").performClick()
        compose.onNodeWithText("°C and km/h").performClick()
        compose.onNodeWithText("Every 5 s, saves battery").performScrollTo().performClick()
        compose.onNodeWithText("Keep the screen on").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithText("For ever").performScrollTo().performClick()
        assertThat(calls).containsExactly(ThemeSetting.TRUE_BLACK, Units.METRIC, "poll 5000", "screen true", "keep null").inOrder()
    }

    @Test
    fun `steppers move one value each`() {
        show(Settings())
        compose.onNode(hasContentDescription("Raise Quality: good from")).performScrollTo().performClick()
        compose.onNode(hasContentDescription("Lower Strength: overload from")).performScrollTo().performClick()
        compose.onNode(hasContentDescription("Raise High pitch")).performScrollTo().performClick()
        assertThat(calls).containsExactly(
            Thresholds.DEFAULT.copy(snqGood = 81),
            Thresholds.DEFAULT.copy(ssOverload = 94),
            ToneRange(200, 1250),
        ).inOrder()
    }

    @Test
    fun `reset is offered once a threshold has moved`() {
        show(Settings(thresholds = Thresholds.DEFAULT.copy(snqGood = 85)))
        compose.onNodeWithText("Reset to defaults").performScrollTo().performClick()
        assertThat(calls).containsExactly("reset")
    }

    @Test
    fun `a set home shows rounded and can be forgotten`() {
        show(Settings(home = Home(39.74, -104.99)))
        compose.onNodeWithText("39.74, -104.99").performScrollTo().assertExists()
        compose.onNodeWithText("Forget home").performScrollTo().performClick()
        assertThat(calls).containsExactly("forget")
    }

    @Test
    fun `nothing but the title until settings load`() {
        show(null)
        compose.onNodeWithText("Settings").assertExists()
        compose.onNodeWithText("Theme").assertDoesNotExist()
    }
}
