package io.cosgriff.sightline.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import io.cosgriff.sightline.R
import io.cosgriff.sightline.ui.theme.SightlineTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The bottom bar: four tabs, Devices first and home, Back from any other tab returns to it. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SightlineNavHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    private fun show() = compose.setContent { SightlineTheme { SightlineNavHost() } }

    private fun tab(label: Int) = compose.onNode(isSelectable() and hasText(text(label)))

    private fun title(id: Int) = compose.onNode(isHeading() and hasText(text(id)))

    @Test
    fun `opens on Devices`() {
        show()
        title(R.string.devices_title).assertExists()
        tab(R.string.nav_devices).assertIsSelected()
    }

    @Test
    fun `each tab shows its screen`() {
        show()
        listOf(
            R.string.nav_live to R.string.live_title,
            R.string.nav_monitor to R.string.monitor_title,
            R.string.nav_history to R.string.history_title,
            R.string.nav_devices to R.string.devices_title,
        ).forEach { (label, screen) ->
            tab(label).performClick()
            title(screen).assertExists()
            tab(label).assertIsSelected()
        }
    }

    @Test
    fun `Back from another tab goes to Devices`() {
        show()
        tab(R.string.nav_live).performClick()
        tab(R.string.nav_monitor).performClick()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        title(R.string.devices_title).assertExists()
        tab(R.string.nav_devices).assertIsSelected()
    }
}
