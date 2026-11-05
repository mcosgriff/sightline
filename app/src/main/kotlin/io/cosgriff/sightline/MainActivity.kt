package io.cosgriff.sightline

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.cosgriff.sightline.data.settings.SettingsRepository
import io.cosgriff.sightline.ui.SightlineNavHost
import io.cosgriff.sightline.ui.theme.SightlineTheme
import io.cosgriff.sightline.ui.theme.ThemeMode
import io.cosgriff.sightline.ui.theme.isDark
import io.cosgriff.sightline.ui.theme.mode
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /** Counts taps on a Monitor notification, each of which shows the Monitor tab. */
    private var monitorRequests by mutableIntStateOf(0)

    @Inject lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) noteMonitorRequest(intent)
        enableEdgeToEdge(statusBarStyle = barStyle(ThemeMode.Default), navigationBarStyle = barStyle(ThemeMode.Default))
        setContent {
            // Nothing is drawn until the setting is read (a moment), so a light theme never flashes dark first.
            val themes = remember { settings.settings.map { it.theme } }
            val setting by themes.collectAsStateWithLifecycle(initialValue = null)
            val mode = setting?.mode() ?: return@setContent
            val systemDark = isSystemInDarkTheme()
            LaunchedEffect(mode, systemDark) {
                enableEdgeToEdge(statusBarStyle = barStyle(mode, systemDark), navigationBarStyle = barStyle(mode, systemDark))
            }
            SightlineTheme(mode) {
                // Gives every screen the theme's background and ink as its default colours.
                Surface(color = MaterialTheme.colorScheme.background) {
                    SightlineNavHost(openMonitorRequests = monitorRequests)
                }
            }
        }
    }

    /** The app can be dark while the phone is light, so the bar icons follow the app, not the phone. */
    private fun barStyle(mode: ThemeMode, systemDark: Boolean = false): SystemBarStyle = if (mode.isDark(systemDark)) {
        SystemBarStyle.dark(Color.TRANSPARENT)
    } else {
        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
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
