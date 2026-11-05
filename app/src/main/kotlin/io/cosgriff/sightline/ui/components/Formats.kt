package io.cosgriff.sightline.ui.components

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.cosgriff.sightline.R
import io.cosgriff.sightline.data.settings.Units
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

// Times, dates, durations and units as Session detail and History show them. Times are stored in UTC epoch ms
// and shown in the phone's zone; values are stored metric and converted here (CLAUDE.md "Units").

private fun format(skeleton: String, ms: Long, zone: ZoneId): String {
    val locale = Locale.getDefault()
    return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(Instant.ofEpochMilli(ms).atZone(zone))
}

/** "3:52 PM". */
fun clockTime(ms: Long, zone: ZoneId): String = format("jmm", ms, zone)

/** "Nov 3". */
fun shortDate(ms: Long, zone: ZoneId): String = format("MMMd", ms, zone)

/** "Nov 3, 3:52 PM". */
fun dateTime(ms: Long, zone: ZoneId): String = format("MMMdjmm", ms, zone)

/** "Nov 3, 6 PM", for chart labels. */
fun dateHour(ms: Long, zone: ZoneId): String = format("MMMdj", ms, zone)

/** "6 PM". */
fun hour(ms: Long, zone: ZoneId): String = format("j", ms, zone)

/** "12 s", "1m 04s", "2h 05m", as the Session mockup writes durations. */
@Composable
fun compactDuration(ms: Long): String {
    val s = ((ms + 500) / 1_000).toInt()
    return when {
        s < 60 -> stringResource(R.string.session_duration_s, s)
        s < 3_600 -> stringResource(R.string.session_duration_ms, s / 60, s % 60)
        else -> stringResource(R.string.session_duration_hm, s / 3_600, (s % 3_600) / 60)
    }
}

/** A session's length: "36 h", or "45 min" under an hour. */
@Composable
fun sessionLength(ms: Long): String {
    val minutes = ((ms + 30_000) / 60_000).toInt()
    return if (minutes < 60) stringResource(R.string.session_minutes, minutes) else stringResource(R.string.session_hours, (minutes + 30) / 60)
}

fun Units.speed(kph: Double): Int = (if (this == Units.METRIC) kph else kph * 0.621371).roundToInt()

fun Units.temperature(celsius: Double): Int = (if (this == Units.METRIC) celsius else celsius * 9 / 5 + 32).roundToInt()

/** "19 mph" or "30 km/h". */
@Composable
fun speedText(kph: Double, units: Units): String =
    stringResource(if (units == Units.METRIC) R.string.session_kph else R.string.session_mph, units.speed(kph))

/** "48 °F" or "9 °C". */
@Composable
fun temperatureText(celsius: Double, units: Units): String =
    stringResource(if (units == Units.METRIC) R.string.session_deg_c else R.string.session_deg_f, units.temperature(celsius))
