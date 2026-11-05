package io.cosgriff.sightline.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Tokens from SPEC "Color tokens". Dynamic (wallpaper) colour is off: a health colour must always mean the same
// thing, and the chrome follows the mockups. Screens take colours from here, never a hex of their own.

/** The design's colour tokens, by their SPEC names. */
@Immutable
data class SightlineColors(
    val bg: Color,
    val surface: Color,
    val outline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val healthGood: Color,
    val healthMarginal: Color,
    val healthPoor: Color,
    val healthNoLock: Color,
    /** The bottom bar: a step off the background, as in the mockups. */
    val bar: Color,
    /**
     * Lines for the second and later channels on a chart; the first is [accent]. From the Session mockup (4.1 in
     * violet). Told apart by the legend's names too, never by colour alone.
     */
    val chartLines: List<Color>,
    /** Weather bars on Session detail: neutral, so the health colours keep their meaning. */
    val weatherBar: Color,
    /** A heatmap cell with nothing below the line. */
    val heatEmpty: Color,
)

private val DarkColors = SightlineColors(
    bg = Color(0xFF0E1114),
    surface = Color(0xFF171B20),
    outline = Color(0xFF2A3138),
    textPrimary = Color(0xFFE8ECEF),
    textSecondary = Color(0xFF9AA4AE),
    accent = Color(0xFF5AA9FF),
    healthGood = Color(0xFF4CC38A),
    healthMarginal = Color(0xFFF2B33D),
    healthPoor = Color(0xFFF2685C),
    healthNoLock = Color(0xFF6B7580),
    bar = Color(0xFF12161A),
    chartLines = listOf(Color(0xFFC99BFF), Color(0xFF4FD1C5), Color(0xFFF28FB8)),
    weatherBar = Color(0xFF3A434C),
    heatEmpty = Color(0xFF232A31),
)

/** True black (OLED) swaps only the backgrounds. */
private val TrueBlackColors = DarkColors.copy(bg = Color(0xFF000000), bar = Color(0xFF000000))

private val LightColors = SightlineColors(
    bg = Color(0xFFF7F9FA),
    surface = Color(0xFFFFFFFF),
    outline = Color(0xFFD5DBE0),
    textPrimary = Color(0xFF111418),
    textSecondary = Color(0xFF5B6670),
    accent = Color(0xFF1E6FD9),
    healthGood = Color(0xFF2E7D32),
    healthMarginal = Color(0xFFB26A00),
    healthPoor = Color(0xFFC62828),
    healthNoLock = Color(0xFF616161),
    bar = Color(0xFFFFFFFF),
    chartLines = listOf(Color(0xFF7B4CC2), Color(0xFF00897B), Color(0xFFC2185B)),
    weatherBar = Color(0xFFB8C2CB),
    heatEmpty = Color(0xFFE6EBEF),
)

/** The theme setting (SPEC Screens › 10). Dark is the default, whatever the phone's own setting. */
enum class ThemeMode {
    System,
    Light,
    Dark,
    TrueBlack,
    ;

    companion object {
        val Default = Dark
    }
}

/** Whether [mode] draws dark, given the phone's own setting [systemDark]. */
fun ThemeMode.isDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.System -> systemDark
    ThemeMode.Light -> false
    ThemeMode.Dark, ThemeMode.TrueBlack -> true
}

private fun ThemeMode.colors(systemDark: Boolean): SightlineColors = when {
    this == ThemeMode.TrueBlack -> TrueBlackColors
    isDark(systemDark) -> DarkColors
    else -> LightColors
}

/** Material's roles filled from the tokens, every container named so nothing falls back to Material's lavender. */
private fun SightlineColors.scheme(dark: Boolean): ColorScheme {
    // Text on an accent fill: dark ink on the light-blue dark-theme accent, white on the deep light-theme one.
    val onAccent = if (dark) DarkColors.bg else Color.White
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = surface,
        onPrimaryContainer = textPrimary,
        secondary = accent,
        onSecondary = onAccent,
        secondaryContainer = surface,
        onSecondaryContainer = textPrimary,
        background = bg,
        onBackground = textPrimary,
        surface = surface,
        onSurface = textPrimary,
        surfaceVariant = surface,
        onSurfaceVariant = textSecondary,
        surfaceContainerLowest = bg,
        surfaceContainerLow = bg,
        surfaceContainer = bar,
        surfaceContainerHigh = surface,
        surfaceContainerHighest = surface,
        outline = outline,
        outlineVariant = outline,
        error = healthPoor,
        onError = onAccent,
    )
}

/** The mockups' corners: 10dp for tuner cells and chips (`small`), 18 for cards (`medium`), 24 for sheets (`large`). */
val SightlineShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
)

val LocalSightlineColors = staticCompositionLocalOf { DarkColors }

@Composable
fun SightlineTheme(mode: ThemeMode = ThemeMode.Default, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val colors = mode.colors(systemDark)
    MaterialTheme(
        colorScheme = colors.scheme(mode.isDark(systemDark)),
        typography = SightlineTypography,
        shapes = SightlineShapes,
    ) {
        CompositionLocalProvider(LocalSightlineColors provides colors, content = content)
    }
}

/** The design tokens for the current theme: health colours and the rest Material doesn't name. */
val MaterialTheme.sightlineColors: SightlineColors
    @Composable @ReadOnlyComposable get() = LocalSightlineColors.current
