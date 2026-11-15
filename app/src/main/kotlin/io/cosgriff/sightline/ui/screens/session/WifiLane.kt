package io.cosgriff.sightline.ui.screens.session

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.cosgriff.sightline.R
import io.cosgriff.sightline.analysis.Health
import io.cosgriff.sightline.analysis.WifiBand
import io.cosgriff.sightline.analysis.WifiSample
import io.cosgriff.sightline.analysis.WifiSignal
import io.cosgriff.sightline.analysis.WifiSummary
import io.cosgriff.sightline.analysis.roamsOf
import io.cosgriff.sightline.ui.components.TimeLabels
import io.cosgriff.sightline.ui.components.color
import io.cosgriff.sightline.ui.components.dateTime
import io.cosgriff.sightline.ui.theme.sightlineColors
import java.time.ZoneId

/** The Wi-Fi lane's dBm scale: the strongest signal drawn full height, the weakest at the bottom. */
internal const val WIFI_TOP_DBM = -30
internal const val WIFI_BOTTOM_DBM = -95

/** How high a bar for [rssiDbm] stands, 0..1 of the lane. */
internal fun wifiHeight(rssiDbm: Int): Float =
    ((rssiDbm - WIFI_BOTTOM_DBM).toFloat() / (WIFI_TOP_DBM - WIFI_BOTTOM_DBM)).coerceIn(0.04f, 1f)

/**
 * The phone's Wi-Fi under the timeline (SPEC Screens › Session detail, "Phone Wi-Fi"), lined up with it over
 * [startMs]..[endMs]: the signal as bars coloured by its health, the weak line dashed, time off Wi-Fi as grey blocks
 * and each roam as a tick with a triangle, the three named in a legend so colour is never alone. [samples] are the
 * whole session's, in time order, each holding until the next or [sessionEndMs]. Under it, [timeLabels] (it is the
 * last lane under the chart), then the Wi-Fi in words and the access points. Only for a session with samples: older ones have no lane.
 */
@Composable
fun WifiLane(
    samples: List<WifiSample>,
    startMs: Long,
    endMs: Long,
    sessionEndMs: Long,
    zone: ZoneId,
    timeLabels: List<String>,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.sightlineColors
    val summary = WifiSummary.of(samples) ?: return
    val shown = samples.filterIndexed { i, s -> s.atMs <= endMs && (samples.getOrNull(i + 1)?.atMs ?: sessionEndMs) >= startMs }
    val words = wifiSummaryText(WifiSummary.of(shown) ?: summary)
    val described = stringResource(R.string.session_wifi_description, dateTime(startMs, zone), dateTime(endMs, zone), words)
    val roams = roamsOf(samples).filter { it in startMs..endMs }
    val healthColors = WifiSignalColors(
        good = Health.GOOD.color().copy(alpha = 0.75f),
        marginal = Health.MARGINAL.color().copy(alpha = 0.75f),
        poor = Health.POOR.color().copy(alpha = 0.75f),
    )
    Column(modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.semantics(mergeDescendants = true) { contentDescription = described }) {
            Text(stringResource(R.string.session_wifi_title), style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
            Canvas(Modifier.fillMaxWidth().height(36.dp).padding(top = 4.dp)) {
                val span = (endMs - startMs).coerceAtLeast(1).toFloat()
                fun x(ms: Long) = (size.width * ((ms - startMs) / span)).coerceIn(0f, size.width)
                for ((i, s) in samples.withIndex()) {
                    val l = x(s.atMs)
                    val r = x(samples.getOrNull(i + 1)?.atMs ?: sessionEndMs)
                    if (r <= l) continue
                    when {
                        !s.connected -> drawRect(colors.healthNoLock.copy(alpha = 0.45f), Offset(l, 0f), Size(r - l, size.height))
                        s.rssiDbm != null -> {
                            val h = size.height * wifiHeight(s.rssiDbm!!)
                            drawRect(healthColors.of(s.rssiDbm!!), Offset(l, size.height - h), Size(r - l, h))
                        }
                        // On Wi-Fi with no signal reading: a sliver, so it isn't taken for off.
                        else -> drawRect(colors.textSecondary.copy(alpha = 0.4f), Offset(l, size.height - 2.dp.toPx()), Size(r - l, 2.dp.toPx()))
                    }
                }
                val weakY = size.height * (1 - wifiHeight(WifiSignal.WEAK_DBM))
                drawLine(
                    colors.textSecondary.copy(alpha = 0.7f), Offset(0f, weakY), Offset(size.width, weakY), 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
                )
                for (at in roams) {
                    val rx = x(at)
                    drawLine(colors.accent, Offset(rx, 0f), Offset(rx, size.height), 1.5.dp.toPx())
                    drawPath(triangle(rx, 0f, 5.dp.toPx()), colors.accent)
                }
                drawLine(colors.outline, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            }
        }
        TimeLabels(timeLabels)
        WifiLegend()
        Text(words, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        accessPointsText(summary)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary) }
    }
}

private class WifiSignalColors(val good: Color, val marginal: Color, val poor: Color) {
    fun of(rssiDbm: Int): Color = when (WifiSignal.health(rssiDbm)) {
        Health.GOOD -> good
        Health.MARGINAL -> marginal
        else -> poor
    }
}

/** A small downward triangle with its point at ([x], [top] + [size]): the roam marker. */
private fun triangle(x: Float, top: Float, size: Float) = Path().apply {
    moveTo(x - size / 2, top)
    lineTo(x + size / 2, top)
    lineTo(x, top + size)
    close()
}

@Composable
private fun WifiLegend() {
    val colors = MaterialTheme.sightlineColors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Canvas(Modifier.size(10.dp)) {
                drawPath(triangle(size.width / 2, 0f, size.width), colors.accent)
            }
            Text(stringResource(R.string.session_wifi_legend_roam), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.size(10.dp).background(colors.healthNoLock.copy(alpha = 0.45f), MaterialTheme.shapes.extraSmall))
            Text(stringResource(R.string.session_wifi_legend_off), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Canvas(Modifier.width(14.dp).height(2.dp)) {
                drawLine(
                    colors.textSecondary, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), size.height,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())),
                )
            }
            Text(
                stringResource(R.string.session_wifi_legend_weak, WifiSignal.WEAK_DBM),
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
            )
        }
    }
}

/** "Signal −48 to −83 dBm · 5 GHz · 2 roams · off Wi-Fi 1 time": the parts known, the roams only when they could be seen. */
@Composable
internal fun wifiSummaryText(s: WifiSummary): String {
    val parts = listOfNotNull(
        if (s.minRssiDbm != null && s.maxRssiDbm != null) stringResource(R.string.session_wifi_signal, s.maxRssiDbm!!, s.minRssiDbm!!) else null,
        s.bands.takeIf { it.isNotEmpty() }?.map { bandText(it) }?.joinToString(stringResource(R.string.session_wifi_list_separator)),
        // A roam needs the BSSID: without it none can be seen, so saying "no roams" would claim too much.
        if (s.accessPoints.isEmpty()) null
        else if (s.roams == 0) stringResource(R.string.session_wifi_no_roams)
        else pluralStringResource(R.plurals.session_wifi_roams, s.roams, s.roams),
        s.disconnects.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.session_wifi_off, it, it) },
    )
    return parts.joinToString(stringResource(R.string.session_wifi_separator))
}

/**
 * The access points seen, by BSSID (the user's choice on #27), or why there are none; null when the phone was never
 * on Wi-Fi in the samples, so there was no access point to know.
 */
@Composable
internal fun accessPointsText(s: WifiSummary): String? =
    if (s.accessPoints.isEmpty()) {
        if (s.accessPointsUnknown) stringResource(R.string.session_wifi_access_points_unknown) else null
    } else {
        pluralStringResource(
            R.plurals.session_wifi_access_points, s.accessPoints.size, s.accessPoints.size,
            s.accessPoints.joinToString(stringResource(R.string.session_wifi_list_separator)),
        )
    }

@Composable
private fun bandText(b: WifiBand): String = stringResource(
    when (b) {
        WifiBand.GHZ_2_4 -> R.string.session_wifi_band_2_4
        WifiBand.GHZ_5 -> R.string.session_wifi_band_5
        WifiBand.GHZ_6 -> R.string.session_wifi_band_6
    },
)
