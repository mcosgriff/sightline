package io.cosgriff.sightline.analysis

import io.cosgriff.sightline.protocol.Band
import java.time.Instant
import java.time.ZoneId

/** One stored reading of a session, for analysis. [atsc3] readings are tagged so analysis can tell them apart. */
data class LoggedReading(
    val atMs: Long,
    val guideNumber: String,
    val tuner: Int,
    val sample: SignalSample,
    val atsc3: Boolean = false,
    /** The tuner's error counters read with it; null when not read (older sessions, no `/tunerN/debug`). */
    val counters: ErrorCounters? = null,
)

/** One hour of weather at home (Open-Meteo), metric. [hourMs] is the UTC epoch ms the hour starts at. */
data class WeatherHour(
    val hourMs: Long,
    val tempC: Double?,
    val windKph: Double?,
    val gustKph: Double?,
    val precipMm: Double?,
) {
    companion object {
        const val HOUR_MS = 3_600_000L
    }
}

/** What the lineup knows about a channel: its RF channel and band, once tuned. */
data class ChannelInfo(val rfChannel: Int?, val band: Band?)

/**
 * A finished session as analysis sees it (SPEC "Session detail"). [readings] in time order; [zone] is the phone's
 * time zone, for hour-of-day figures.
 */
data class SessionData(
    val startMs: Long,
    val endMs: Long,
    val pollIntervalMs: Long,
    val readings: List<LoggedReading>,
    val events: List<SignalEvent>,
    val weather: List<WeatherHour> = emptyList(),
    val channels: Map<String, ChannelInfo> = emptyMap(),
    val zone: ZoneId = ZoneId.of("UTC"),
) {
    /** Guide numbers in the order first read. */
    val guideNumbers: List<String> by lazy { readings.map { it.guideNumber }.distinct() }

    val byChannel: Map<String, List<LoggedReading>> by lazy { readings.groupBy { it.guideNumber } }

    /** The weather hour [atMs] falls in, if fetched. */
    fun weatherAt(atMs: Long): WeatherHour? = weatherByHour[Math.floorDiv(atMs, WeatherHour.HOUR_MS) * WeatherHour.HOUR_MS]

    private val weatherByHour by lazy { weather.associateBy { it.hourMs } }

    /** The local hour of day (0–23) at [atMs]. */
    fun hourOfDay(atMs: Long): Int = Instant.ofEpochMilli(atMs).atZone(zone).hour
}

/** One heatmap cell: the share of an hour of day's readings below the good line, and when it was worst. */
data class HourCell(
    /** 0..1. */
    val belowShare: Double,
    /** The start (UTC epoch ms) of the clock hour, on whichever day, with the most readings below the line. */
    val worstHourMs: Long,
)

/** The 5-point histogram bins of locked snq: bin i holds snq 5i to 5i+4, the last 95 to 100. */
data class SnqHistogram(val counts: List<Int>) {
    /** The lowest snq in bin [i], which also gives its health band. */
    fun lowerBound(i: Int) = i * BIN_WIDTH

    companion object {
        const val BIN_WIDTH = 5
        const val BINS = 20

        fun of(snqs: List<Int>): SnqHistogram {
            val counts = IntArray(BINS)
            for (v in snqs) counts[(v.coerceIn(0, 100) / BIN_WIDTH).coerceAtMost(BINS - 1)]++
            return SnqHistogram(counts.toList())
        }
    }
}

/**
 * The figures Session detail and History show (SPEC Screens › Session detail). Shares count readings: polls are
 * evenly spaced, so a share of readings is a share of the time read.
 */
data class SessionStats(
    val readings: Int,
    /** 0..1 of readings in good health; null with no readings. */
    val goodShare: Double?,
    val dips: Int,
    /** The longest dropout (a dip with lock lost); null when there was none. */
    val longestDropoutMs: Long?,
    /** Median snq over the locked readings; null when it never locked. */
    val medianSnq: Int?,
    /** The health of the median reading: what a session's chip says. Null with no readings. */
    val health: Health?,
    /** Each channel's snq range, in the order first read. */
    val channels: List<SweptChannel>,
    val histogram: SnqHistogram,
    /** Per channel, 24 cells by local hour of day; null for an hour never read. */
    val hours: Map<String, List<HourCell?>>,
    /** The packet errors each reading added; [PacketErrorSeries.hasCounters] false when none were recorded. */
    val errors: PacketErrorSeries = PacketErrorSeries.of(emptyList()),
) {
    companion object {
        fun of(data: SessionData, thresholds: Thresholds): SessionStats {
            val readings = data.readings
            val locked = readings.filter { it.sample.locked }
            val good = readings.count { thresholds.assess(it.sample).health == Health.GOOD }
            val health = if (locked.isEmpty()) {
                if (readings.isEmpty()) null else Health.NO_LOCK
            } else {
                thresholds.assess(
                    SignalSample(
                        true,
                        median(locked.map { it.sample.ss })!!,
                        median(locked.map { it.sample.snq })!!,
                        median(locked.map { it.sample.seq })!!,
                    ),
                ).health
            }
            return SessionStats(
                readings = readings.size,
                goodShare = if (readings.isEmpty()) null else good.toDouble() / readings.size,
                dips = data.events.dipCount(),
                longestDropoutMs = data.events.filter { it.kind == EventKind.DROPOUT }.maxOfOrNull { it.durationMs },
                medianSnq = median(locked.map { it.sample.snq }),
                health = health,
                channels = data.guideNumbers.map { g ->
                    SweptChannel.of(g, data.byChannel.getValue(g).map { it.sample }, thresholds)
                },
                histogram = SnqHistogram.of(locked.map { it.sample.snq }),
                hours = data.byChannel.mapValues { (_, rs) -> hourCells(data, rs, thresholds) },
                errors = PacketErrorSeries.of(readings),
            )
        }

        /** Below the good line, or no lock: what the heatmap counts (mockup: "Share of each hour below quality 80"). */
        private fun below(s: SignalSample, t: Thresholds) = !s.locked || s.snq < t.snqGood

        private fun hourCells(data: SessionData, rs: List<LoggedReading>, t: Thresholds): List<HourCell?> {
            val byClockHour = rs.groupBy { Math.floorDiv(it.atMs, WeatherHour.HOUR_MS) * WeatherHour.HOUR_MS }
            val byHourOfDay = byClockHour.entries.groupBy { data.hourOfDay(it.key) }
            return (0 until 24).map { h ->
                val hours = byHourOfDay[h] ?: return@map null
                val total = hours.sumOf { it.value.size }
                val below = hours.sumOf { e -> e.value.count { below(it.sample, t) } }
                val worst = hours.maxWith(compareBy<Map.Entry<Long, List<LoggedReading>>> { e -> e.value.count { below(it.sample, t) } }.thenByDescending { it.key })
                HourCell(below.toDouble() / total, worst.key)
            }
        }
    }
}

/**
 * The lowest snq per stretch of [startMs]..[endMs] for one channel, sized so the window fits without the series
 * doubling its buckets: series for several channels built with the same window line up.
 */
fun timelineOf(readings: List<LoggedReading>, startMs: Long, endMs: Long, maxBuckets: Int = TimelineSeries.DEFAULT_MAX_BUCKETS): TimelineSeries {
    val span = (endMs - startMs).coerceAtLeast(1)
    val bucket = ((span + maxBuckets - 1) / maxBuckets).coerceAtLeast(1)
    var series = TimelineSeries(startMs, bucket, maxBuckets + 1)
    for (r in readings) if (r.atMs in startMs..endMs) series += r.atMs to r.sample
    return series
}

/** [points] lows of snq across the session, oldest first, for History's tiny sparkline; null where nothing was read. */
fun sparklineOf(readings: List<LoggedReading>, startMs: Long, endMs: Long, points: Int = 48): List<Int?> {
    val series = timelineOf(readings, startMs, endMs, points)
    return (0 until points).map { series.lows[it] }
}
