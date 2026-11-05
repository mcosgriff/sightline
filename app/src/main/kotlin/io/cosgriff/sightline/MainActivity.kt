package io.cosgriff.sightline

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import dagger.hilt.android.AndroidEntryPoint
import io.cosgriff.sightline.ui.SightlineNavHost
import io.cosgriff.sightline.ui.theme.SightlineTheme
import io.cosgriff.sightline.ui.theme.ThemeMode
import io.cosgriff.sightline.ui.theme.isDark

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                    SightlineNavHost()
                }
            }
        }
    }
}
