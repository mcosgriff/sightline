package io.cosgriff.sightline.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import io.cosgriff.sightline.analysis.AimPeak
import io.cosgriff.sightline.analysis.AimPoint
import io.cosgriff.sightline.analysis.Best
import io.cosgriff.sightline.analysis.Health
import io.cosgriff.sightline.analysis.HourCell
import io.cosgriff.sightline.analysis.Thresholds
import io.cosgriff.sightline.ui.screens.session.HourHeatmap
import io.cosgriff.sightline.ui.theme.Numbers
import io.cosgriff.sightline.ui.theme.SightlineTheme
import io.cosgriff.sightline.ui.theme.ThemeMode
import io.cosgriff.sightline.ui.theme.sightlineColors
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneOffset
import kotlin.math.cos

/**
 * Screenshots of the custom Canvas components (SPEC "Screenshot tests") in the three themes, compared with the
 * golden images in `app/src/test/screenshots`. After a deliberate change, `./gradlew recordRoborazziDebug` redraws
 * them; look at the new images before committing.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35], qualifiers = "en-rUS-w400dp-h800dp-xhdpi")
class ComponentScreenshotTest(private val theme: ThemeMode) {
    private fun snap(name: String, content: @Composable () -> Unit) = captureRoboImage(
        filePath = "src/test/screenshots/${name}_${theme.name}.png",
        roborazziOptions = options,
    ) {
        // As MainActivity draws every screen: on a Surface, which gives text its default colour.
        SightlineTheme(theme) {
            Surface(color = MaterialTheme.colorScheme.background) { Box(Modifier.padding(16.dp)) { content() } }
        }
    }

    @Test
    fun arcGauge() = snap("ArcGauge") {
        val colors = MaterialTheme.sightlineColors
        ArcGauge(
            value = 72,
            color = Health.MARGINAL.color(),
            trackColor = colors.outline,
            markColor = colors.textPrimary,
            holeColor = colors.bg,
            stroke = 14.dp,
            description = "",
            threshold = 80,
            peak = 86,
            modifier = Modifier.size(width = 200.dp, height = 170.dp),
        ) { Text("72", style = Numbers.gaugeLive) }
    }

    @Test
    fun triGauge() = snap("TriGauge") {
        TriGauge(GaugeValues(ss = 96, snq = 86, seq = 100), Best(97, 91, 100), Thresholds.DEFAULT, Modifier.width(368.dp))
    }

    @Test
    fun sparkline() = snap("Sparkline") {
        val colors = MaterialTheme.sightlineColors
        val points = (0..60).map { s -> SparkPoint(s * 1_000L, if (s in 30..33) null else (82 + 8 * cos(s / 4.0)).toInt()) }
        Sparkline(points, 60_000, colors.accent, colors.textSecondary, "", Modifier.fillMaxWidth().height(56.dp), threshold = 80)
    }

    @Test
    fun channelRangeBars() = snap("ChannelRangeBars") {
        ChannelRangeBars(
            listOf(
                RangeRow("7.1", "7.1", "RF 7 · VHF high", null, null, null, null),
                RangeRow("2.1", "2.1", "RF 35 · UHF", 31, 54, 66, Health.POOR),
                RangeRow("4.1", "4.1", "RF 23 · UHF", 66, 78, 84, Health.MARGINAL),
                RangeRow("9.1", "9.1", "RF 9 · VHF high", 84, 91, 95, Health.GOOD),
            ),
            threshold = 80,
            modifier = Modifier.width(368.dp),
        )
    }

    @Test
    fun polarAim() = snap("PolarAim") {
        val points = (0 until 360 step 3).map { d -> AimPoint(d.toDouble(), (60 + 30 * cos(Math.toRadians(d - 145.0))).toInt().coerceIn(0, 100)) }
        PolarAim(points, headingDeg = 150.0, best = AimPeak(90, 145), towers = listOf(140, 262), description = "", modifier = Modifier.size(368.dp))
    }

    @Test
    fun hourHeatmap() = snap("HourHeatmap") {
        val rows = listOf("9.1", "4.1").mapIndexed { row, guide ->
            guide to (0 until 24).map { h -> if (row == 1 && h == 3) null else HourCell(if (h in 12..17) (0.05 + 0.1 * (h - 12) + row * 0.1) else 0.0, h * 3_600_000L) }
        }
        HourHeatmap(rows, ZoneOffset.UTC, onHour = {}, modifier = Modifier.width(368.dp))
    }

    /** Anti-aliasing can differ by a pixel between machines; anything bigger is a real change. */
    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.005f))

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun themes() = listOf(ThemeMode.Dark, ThemeMode.Light, ThemeMode.TrueBlack)
    }
}
