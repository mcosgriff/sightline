package io.cosgriff.sightline.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import io.cosgriff.sightline.ui.screens.devices.DevicesScreen
import io.cosgriff.sightline.ui.screens.history.HistoryScreen
import io.cosgriff.sightline.ui.screens.live.LiveScreen
import io.cosgriff.sightline.ui.screens.monitor.MonitorScreen
import io.cosgriff.sightline.ui.theme.sightlineColors

/** The bottom bar's four destinations (SPEC Screens), in order. */
private enum class TopLevel(val key: NavKey, @param:StringRes val label: Int, val icon: ImageVector) {
    DevicesTab(Devices, R.string.nav_devices, Icons.Outlined.Tv),
    LiveTab(Live, R.string.nav_live, Icons.Outlined.Speed),
    MonitorTab(Monitor, R.string.nav_monitor, Icons.Outlined.MonitorHeart),
    HistoryTab(History, R.string.nav_history, Icons.Outlined.History),
}

/**
 * The app's screens under the bottom bar. Devices is home: picking another tab puts it over Devices, so Back from
 * any tab returns there, and Back on Devices leaves the app. Screens opened from a tab (Aim, Sweep) go on top of
 * it, and the bar keeps that tab selected.
 */
@Composable
fun SightlineNavHost() {
    val backStack = rememberNavBackStack(Devices)
    val selected = TopLevel.entries.lastOrNull { tab -> backStack.lastOrNull { it in topLevelKeys } == tab.key }
    Scaffold(
        bottomBar = { BottomBar(selected = selected, onSelect = { backStack.showTab(it.key) }) },
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
                entry<Devices> { DevicesScreen() }
                entry<Live> { LiveScreen() }
                entry<Monitor> { MonitorScreen() }
                entry<History> { HistoryScreen() }
            },
        )
    }
}

private val topLevelKeys = TopLevel.entries.map { it.key }.toSet()

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
