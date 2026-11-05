package io.cosgriff.sightline.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.cosgriff.sightline.R
import io.cosgriff.sightline.debugtools.DebugTools
import io.cosgriff.sightline.debugtools.ProtocolDebugEntry
import io.cosgriff.sightline.ui.screens.aim.AimScreen
import io.cosgriff.sightline.ui.screens.devices.DevicesScreen
import io.cosgriff.sightline.ui.screens.history.HistoryScreen
import io.cosgriff.sightline.ui.screens.live.LiveActions
import io.cosgriff.sightline.ui.screens.live.LiveScreen
import io.cosgriff.sightline.ui.screens.monitor.MonitorScreen
import io.cosgriff.sightline.ui.screens.session.SessionScreen
import io.cosgriff.sightline.ui.screens.settings.SettingsScreen
import io.cosgriff.sightline.ui.screens.sweep.SweepScreen
import io.cosgriff.sightline.ui.theme.sightlineColors

/** The bottom bar's four destinations (SPEC Screens), in order. */
private enum class TopLevel(val key: NavKey, @param:StringRes val label: Int, val icon: ImageVector) {
    DevicesTab(Devices, R.string.nav_devices, Icons.Outlined.Tv),
    LiveTab(Live, R.string.nav_live, Icons.Outlined.Speed),
    MonitorTab(Monitor, R.string.nav_monitor, Icons.Outlined.MonitorHeart),
    HistoryTab(History, R.string.nav_history, Icons.Outlined.History),
}

/** What a screen can do to navigation. */
interface Navigator {
    /** Shows a bottom-bar destination, over Devices. */
    fun showTab(key: NavKey)

    /** Opens a screen on top of the current one (Aim and Sweep over Live). */
    fun push(key: NavKey)

    /** Closes the top screen. */
    fun back()
}

/**
 * The app's screens under the bottom bar. Devices is home: picking another tab puts it over Devices, so Back from
 * any tab returns there, and Back on Devices leaves the app. Screens opened from a tab (Aim, Sweep) go on top of
 * it, and the bar keeps that tab selected.
 */
@Composable
fun SightlineNavHost(
    openMonitorRequests: Int = 0,
    screen: @Composable (NavKey, Navigator) -> Unit = { key, navigator -> AppScreen(key, navigator) },
) {
    val backStack = rememberNavBackStack(Devices)
    // A tap on a Monitor notification.
    LaunchedEffect(openMonitorRequests) { if (openMonitorRequests > 0) backStack.showTab(Monitor) }
    val navigator = remember(backStack) {
        object : Navigator {
            override fun showTab(key: NavKey) = backStack.showTab(key)
            override fun push(key: NavKey) {
                backStack.add(key)
            }

            override fun back() {
                if (backStack.size > 1) backStack.removeLastOrNull()
            }
        }
    }
    val selected = TopLevel.entries.lastOrNull { tab -> backStack.lastOrNull { it in topLevelKeys } == tab.key }
    // Aim, Sweep and Session detail fill the screen, as in their mockups; their back arrow returns to where they opened.
    val fullScreen = backStack.lastOrNull().let { it in fullScreenKeys || it is Session }
    Scaffold(
        bottomBar = { if (!fullScreen) BottomBar(selected = selected, onSelect = { backStack.showTab(it.key) }) },
        // Full-screen pages paint their own background under the system bars and pad themselves.
        contentWindowInsets = if (fullScreen) WindowInsets(0) else ScaffoldDefaults.contentWindowInsets,
    ) { padding ->
        NavDisplay(
            backStack = backStack,
            modifier = Modifier.padding(padding),
            onBack = { backStack.removeLastOrNull() },
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = entryProvider {
                entry<Devices> { screen(Devices, navigator) }
                entry<Live> { screen(Live, navigator) }
                entry<Monitor> { screen(Monitor, navigator) }
                entry<History> { screen(History, navigator) }
                entry<Aim> { screen(Aim, navigator) }
                entry<Sweep> { screen(Sweep, navigator) }
                entry<Settings> { screen(Settings, navigator) }
                entry<Session> { screen(it, navigator) }
                entry<ProtocolDebug> { screen(ProtocolDebug, navigator) }
            },
        )
    }
}

/** The real screens, each wired to navigation. */
@Composable
private fun AppScreen(key: NavKey, navigator: Navigator) {
    when (key) {
        Devices -> DevicesScreen(
            onOpenLive = { navigator.showTab(Live) },
            onOpenProtocolDebug = if (DebugTools.AVAILABLE) ({ navigator.push(ProtocolDebug) }) else null,
        )
        Live -> LiveScreen(
            LiveActions(
                onAim = { navigator.push(Aim) },
                onSweep = { navigator.push(Sweep) },
                onMonitor = { navigator.showTab(Monitor) },
                onSettings = { navigator.push(Settings) },
                onDevices = { navigator.showTab(Devices) },
            ),
        )
        Monitor -> MonitorScreen(onOpenSession = { navigator.push(Session(it)) })
        History -> HistoryScreen(onOpen = { navigator.push(Session(it)) })
        is Session -> SessionScreen(key.id, onBack = navigator::back)
        Aim -> AimScreen(onBack = navigator::back)
        Sweep -> SweepScreen(onBack = navigator::back)
        Settings -> SettingsScreen(onBack = navigator::back)
        ProtocolDebug -> ProtocolDebugEntry()
    }
}

private val topLevelKeys = TopLevel.entries.map { it.key }.toSet()
private val fullScreenKeys = setOf<NavKey>(Aim, Sweep)

private fun NavBackStack<NavKey>.showTab(key: NavKey) {
    if (lastOrNull() == key) return
    clear()
    add(Devices)
    if (key != Devices) add(key)
}

@Composable
private fun BottomBar(selected: TopLevel?, onSelect: (TopLevel) -> Unit) {
    val colors = MaterialTheme.sightlineColors
    Column {
        HorizontalDivider(color = colors.outline)
        NavigationBar(containerColor = colors.bar) {
            TopLevel.entries.forEach { tab ->
                NavigationBarItem(
                    selected = tab == selected,
                    onClick = { onSelect(tab) },
                    icon = { Icon(tab.icon, contentDescription = null) },
                    label = { Text(stringResource(tab.label)) },
                    // The mockups mark the selected tab by colour and weight alone, with no pill behind the icon.
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = colors.accent,
                        selectedTextColor = colors.accent,
                        unselectedIconColor = colors.textSecondary,
                        unselectedTextColor = colors.textSecondary,
                        indicatorColor = Color.Transparent,
                    ),
                )
            }
        }
    }
}
