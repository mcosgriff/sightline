package io.cosgriff.sightline

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import dagger.hilt.android.AndroidEntryPoint
import io.cosgriff.sightline.ui.SightlineNavHost
import io.cosgriff.sightline.ui.theme.SightlineTheme
import io.cosgriff.sightline.ui.theme.ThemeMode
import io.cosgriff.sightline.ui.theme.isDark

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /** Counts taps on a Monitor notification, each of which shows the Monitor tab. */
    private var monitorRequests by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) noteMonitorRequest(intent)
        // The theme setting arrives with Settings (milestone 6); until then, the default.
        val mode = ThemeMode.Default
        // The app can be dark while the phone is light, so the bar icons follow the app, not the phone.
        val bars = if (mode.isDark(systemDark = false)) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        setContent {
            SightlineTheme(mode) {
                // Gives every screen the theme's background and ink as its default colours.
                Surface(color = MaterialTheme.colorScheme.background) {
                    SightlineNavHost(openMonitorRequests = monitorRequests)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        noteMonitorRequest(intent)
    }

    private fun noteMonitorRequest(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_MONITOR, false) == true) monitorRequests++
    }

    companion object {
        /** Set by the Monitor notifications: open on the Monitor tab. */
        const val EXTRA_OPEN_MONITOR = "io.cosgriff.sightline.OPEN_MONITOR"
    }
}
