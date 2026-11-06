package io.cosgriff.sightline.ui.screens.session

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.cosgriff.sightline.R
import io.cosgriff.sightline.analysis.ChannelLevel
import io.cosgriff.sightline.analysis.DipStretch
import io.cosgriff.sightline.analysis.Finding
import io.cosgriff.sightline.analysis.WeatherCause
import io.cosgriff.sightline.data.settings.Units
import io.cosgriff.sightline.ui.components.compactDuration
import io.cosgriff.sightline.ui.components.hour
import io.cosgriff.sightline.ui.components.dateHour
import io.cosgriff.sightline.ui.components.shortDate
import io.cosgriff.sightline.ui.components.speedText
import io.cosgriff.sightline.ui.components.temperatureText
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/** A finding in words: what the card says. */
data class FindingText(val title: String, val evidence: String, val fix: String)

/** Words a finding from `:analysis` (which returns no English), its numbers converted to [units]. */
@Composable
fun Finding.text(units: Units, zone: ZoneId): FindingText = when (this) {
    is Finding.Overload -> FindingText(
        stringResource(R.string.session_finding_overload),
        levels(channels, R.string.session_evidence_overload_one, R.plurals.session_evidence_overload_more),
        stringResource(R.string.session_fix_overload),
    )
    is Finding.WeakSignal -> FindingText(
        stringResource(R.string.session_finding_weak),
        levels(channels, R.string.session_evidence_weak_one, R.plurals.session_evidence_weak_more),
        stringResource(R.string.session_fix_weak),
    )
    is Finding.OneChannelBad -> FindingText(
        stringResource(R.string.session_finding_one_channel, guideNumber),
        when {
            others.size == 1 && everyHour -> stringResource(R.string.session_evidence_one_channel_every_hour, medianSnq, guideNumber, othersMedianSnq, others.single())
            others.size == 1 -> stringResource(R.string.session_evidence_one_channel, medianSnq, guideNumber, othersMedianSnq, others.single())
            everyHour -> stringResource(R.string.session_evidence_one_channel_others_every_hour, medianSnq, guideNumber, othersMedianSnq)
            else -> stringResource(R.string.session_evidence_one_channel_others, medianSnq, guideNumber, othersMedianSnq)
        },
        stringResource(R.string.session_fix_one_channel),
    )
    is Finding.VhfWeak -> FindingText(
        stringResource(R.string.session_finding_vhf),
        stringResource(R.string.session_evidence_vhf, vhfMedianSnq, list(vhfChannels), uhfMedianSnq),
        stringResource(R.string.session_fix_vhf),
    )
    is Finding.UpperUhfInterference -> FindingText(
        stringResource(R.string.session_finding_upper_uhf),
        ratio?.let { stringResource(R.string.session_evidence_upper_uhf_ratio, ratio(it), list(upperChannels)) }
            ?: pluralStringResource(R.plurals.session_evidence_upper_uhf_only, upperDips, upperDips, list(upperChannels)),
        stringResource(R.string.session_fix_upper_uhf),
    )
    is Finding.TimeOfDay -> {
        // Any day will do for naming the hours: only the clock time is shown.
        val from = hourOfDay(startHour, zone)
        val to = hourOfDay((startHour + 4) % 24, zone)
        FindingText(
            stringResource(R.string.session_finding_time_of_day),
            pluralStringResource(R.plurals.session_evidence_time_of_day, days, (share * 100).roundToInt(), from, to, days),
            stringResource(R.string.session_fix_time_of_day),
        )
    }
    is Finding.FollowsWeather -> weatherText(this, units, zone)
    is Finding.CommonPath -> FindingText(
        stringResource(R.string.session_finding_common_path),
        pluralStringResource(R.plurals.session_evidence_common_path, totalDips, sharedDips, totalDips),
        stringResource(R.string.session_fix_common_path),
    )
    is Finding.OneTuner -> {
        val others = list(otherTuners.map { it.toString() })
        FindingText(
            stringResource(R.string.session_finding_one_tuner),
            if (otherDips == 0) {
                pluralStringResource(R.plurals.session_evidence_one_tuner_only, dips, guideNumber, dips, tuner, others)
            } else {
                pluralStringResource(R.plurals.session_evidence_one_tuner, otherDips, guideNumber, dips, tuner, otherDips, others)
            },
            stringResource(R.string.session_fix_one_tuner),
        )
    }
    is Finding.ImpulseNoise -> FindingText(
        stringResource(R.string.session_finding_impulse),
        lowestSeq?.let { pluralStringResource(R.plurals.session_evidence_impulse, totalDips, count, totalDips, it) }
            ?: pluralStringResource(R.plurals.session_evidence_impulse_no_seq, totalDips, count, totalDips),
        stringResource(R.string.session_fix_impulse),
    )
    is Finding.Flutter -> FindingText(
        stringResource(R.string.session_finding_flutter),
        pluralStringResource(R.plurals.session_evidence_flutter, swings, swings, list(channels)),
        stringResource(R.string.session_fix_flutter),
    )
    is Finding.NetworkLoss -> FindingText(
        stringResource(R.string.session_finding_network_loss),
        networkErrors.toInt().let { n ->
            if (signalErrors == 0L) pluralStringResource(R.plurals.session_evidence_network_loss_clean, n, n)
            else pluralStringResource(R.plurals.session_evidence_network_loss, n, n, signalErrors)
        },
        stringResource(R.string.session_fix_network_loss),
    )
    is Finding.NotRf -> FindingText(
        stringResource(R.string.session_finding_not_rf),
        pluralStringResource(R.plurals.session_evidence_not_rf, count, count, compactDuration(totalMs)),
        stringResource(R.string.session_fix_not_rf),
    )
}

@Composable
private fun weatherText(f: Finding.FollowsWeather, units: Units, zone: ZoneId): FindingText {
    val ratio = f.ratio
    val main = when (f.cause) {
        WeatherCause.WIND -> {
            val line = speedText(f.line, units)
            ratio?.let { stringResource(R.string.session_evidence_wind_ratio, ratio(it), line) }
                ?: pluralStringResource(R.plurals.session_evidence_wind_only, f.dips, f.dips, line)
        }
        WeatherCause.RAIN -> ratio?.let { stringResource(R.string.session_evidence_rain_ratio, ratio(it)) }
            ?: pluralStringResource(R.plurals.session_evidence_rain_only, f.dips, f.dips)
        WeatherCause.HEAT -> {
            val line = temperatureText(f.line, units)
            ratio?.let { stringResource(R.string.session_evidence_heat_ratio, ratio(it), line) }
                ?: pluralStringResource(R.plurals.session_evidence_heat_only, f.dips, f.dips, line)
        }
    }
    val evidence = f.peak?.let { main + " " + peak(it, f.totalDips, zone) } ?: main
    return when (f.cause) {
        WeatherCause.WIND -> FindingText(stringResource(R.string.session_finding_wind), evidence, stringResource(R.string.session_fix_wind))
        WeatherCause.RAIN -> FindingText(stringResource(R.string.session_finding_rain), evidence, stringResource(R.string.session_fix_rain))
        WeatherCause.HEAT -> FindingText(stringResource(R.string.session_finding_heat), evidence, stringResource(R.string.session_fix_heat))
    }
}

/** "19 of 23 dips fell on Nov 3 between 12 PM and 6 PM", or across days with dates on both ends. */
@Composable
private fun peak(p: DipStretch, total: Int, zone: ZoneId): String {
    val start = Instant.ofEpochMilli(p.startMs).atZone(zone)
    val end = Instant.ofEpochMilli(p.endMs).atZone(zone)
    // An end on the next midnight still counts as the same day.
    val sameDay = start.toLocalDate() == end.minusNanos(1).toLocalDate()
    return if (sameDay) {
        pluralStringResource(R.plurals.session_evidence_peak_day, total, p.dips, total, shortDate(p.startMs, zone), hour(p.startMs, zone), hour(p.endMs, zone))
    } else {
        pluralStringResource(R.plurals.session_evidence_peak_span, total, p.dips, total, dateHour(p.startMs, zone), dateHour(p.endMs, zone))
    }
}

@Composable
private fun levels(channels: List<ChannelLevel>, one: Int, more: Int): String {
    val worst = channels.minBy { it.medianSnq }
    val others = channels.size - 1
    return if (others == 0) {
        stringResource(one, worst.medianSs, worst.medianSnq, worst.guideNumber)
    } else {
        pluralStringResource(more, others, worst.medianSs, worst.medianSnq, worst.guideNumber, others)
    }
}

@Composable
private fun list(items: List<String>): String = items.joinToString(stringResource(R.string.session_list_separator))

@Composable
private fun ratio(value: Double): String = stringResource(R.string.session_ratio, value)

/** The clock hour [h] (0–23) as the phone writes it, "2 PM". */
private fun hourOfDay(h: Int, zone: ZoneId): String =
    hour(java.time.LocalDate.of(2026, 1, 1).atTime(h, 0).atZone(zone).toInstant().toEpochMilli(), zone)
