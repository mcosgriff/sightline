package io.cosgriff.sightline.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.action.actionStartService
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.cosgriff.sightline.MainActivity
import io.cosgriff.sightline.R
import io.cosgriff.sightline.analysis.Health
import io.cosgriff.sightline.data.settings.SettingsRepository
import io.cosgriff.sightline.monitor.MonitorController
import io.cosgriff.sightline.monitor.MonitorService
import io.cosgriff.sightline.ui.theme.DarkColors
import io.cosgriff.sightline.ui.theme.LightColors
import io.cosgriff.sightline.ui.theme.SightlineColors
import kotlinx.coroutines.flow.first
import java.time.Clock

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MonitorWidgetEntryPoint {
    fun monitor(): MonitorController
    fun settings(): SettingsRepository
    fun clock(): Clock
}

/**
 * The home-screen widget (SPEC Screens › 7): the running monitor's latest quality and health, its dips and how
 * long it has run, and Stop; with no monitor running, a tap opens Monitor. Follows the phone's light or dark mode,
 * in the app's colour tokens.
 */
class MonitorWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val deps = EntryPointAccessors.fromApplication(context.applicationContext, MonitorWidgetEntryPoint::class.java)
        val thresholds = deps.settings().settings.first().thresholds
        provideContent {
            val status by deps.monitor().status.collectAsState()
            Content(monitorWidgetView(context, status, thresholds, deps.clock().millis()))
        }
    }
}

class MonitorWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MonitorWidget()
}

private fun token(pick: (SightlineColors) -> Color): ColorProvider = ColorProvider(day = pick(LightColors), night = pick(DarkColors))

private fun healthColor(health: Health): ColorProvider = when (health) {
    Health.GOOD -> token { it.healthGood }
    Health.MARGINAL -> token { it.healthMarginal }
    Health.POOR -> token { it.healthPoor }
    Health.NO_LOCK -> token { it.healthNoLock }
}

@Composable
private fun Content(view: MonitorWidgetView) {
    val context = LocalContext.current
    val open = actionStartActivity(
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_MONITOR, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
    )
    val primary = token { it.textPrimary }
    val secondary = token { it.textSecondary }
    Column(
        GlanceModifier.fillMaxSize()
            .background(token { it.surface })
            .cornerRadius(18.dp)
            .padding(14.dp)
            .clickable(open),
    ) {
        when (view) {
            is MonitorWidgetView.Idle -> {
                Text(view.title, style = TextStyle(color = primary, fontSize = 15.sp, fontWeight = FontWeight.Medium))
                Spacer(GlanceModifier.height(4.dp))
                Text(view.body, style = TextStyle(color = secondary, fontSize = 13.sp))
            }
            is MonitorWidgetView.Ended -> {
                Text(view.title, style = TextStyle(color = primary, fontSize = 15.sp, fontWeight = FontWeight.Medium))
                Spacer(GlanceModifier.height(4.dp))
                Text(view.body, style = TextStyle(color = secondary, fontSize = 13.sp))
            }
            is MonitorWidgetView.Running -> {
                Text(view.title, style = TextStyle(color = secondary, fontSize = 13.sp), maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        view.quality ?: context.getString(R.string.gauge_no_value),
                        style = TextStyle(color = primary, fontSize = 34.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace),
                    )
                    Spacer(GlanceModifier.width(10.dp))
                    view.healthWord?.let { word ->
                        Text(
                            word,
                            style = TextStyle(color = view.health?.let(::healthColor) ?: secondary, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                            maxLines = 2,
                        )
                    }
                }
                Text(view.detail, style = TextStyle(color = secondary, fontSize = 13.sp), maxLines = 1)
                Spacer(GlanceModifier.height(8.dp))
                val stop = context.getString(R.string.monitor_stop)
                Text(
                    stop,
                    style = TextStyle(color = token { it.accent }, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    modifier = GlanceModifier
                        .padding(vertical = 6.dp)
                        .clickable(actionStartService(Intent(context, MonitorService::class.java).setAction(MonitorService.ACTION_STOP), isForegroundService = false))
                        .semantics { contentDescription = context.getString(R.string.widget_stop_description) },
                )
            }
        }
    }
}
