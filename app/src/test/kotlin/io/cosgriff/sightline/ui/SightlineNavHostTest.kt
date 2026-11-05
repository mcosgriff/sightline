package io.cosgriff.sightline.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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

    /** Each destination as just its title: this tests navigation, not the screens (they have their own tests). */
    private fun show() = compose.setContent {
        SightlineTheme {
            SightlineNavHost { key, navigator ->
                val title = when (key) {
                    Devices -> R.string.devices_title
                    Live -> R.string.live_title
                    Monitor -> R.string.monitor_title
                    History -> R.string.history_title
                    Aim -> R.string.aim_title
                    else -> R.string.app_name
                }
                Column {
                    if (key == Live) Button(onClick = { navigator.push(Aim) }) { Text("open aim") }
                    if (key == Aim) Button(onClick = navigator::back) { Text("close aim") }
                    Text(stringResource(title), modifier = Modifier.semantics { heading() })
                }
            }
        }
    }

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

    @Test
    fun `Aim opens over Live without the bar and its back returns to Live`() {
        show()
        tab(R.string.nav_live).performClick()
        compose.onNodeWithText("open aim").performClick()
        title(R.string.aim_title).assertExists()
        tab(R.string.nav_live).assertDoesNotExist()
        compose.onNodeWithText("close aim").performClick()
        title(R.string.live_title).assertExists()
        tab(R.string.nav_live).assertIsSelected()
    }
}
