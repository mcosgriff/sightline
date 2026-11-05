package io.cosgriff.sightline.analysis

/**
 * snq over a whole monitor session, kept small enough to draw live (SPEC `SignalTimeline`): readings fall into
 * buckets of [bucketMs] from [startMs], each keeping its lowest snq (no lock counts as 0), so a one-second dip
 * still shows in a 12-hour chart. When there would be more than [maxBuckets], neighbouring buckets are joined and
 * the width doubles. Immutable; [plus] returns the next series.
 */
data class TimelineSeries(
    val startMs: Long,
    val bucketMs: Long,
    val maxBuckets: Int = DEFAULT_MAX_BUCKETS,
    /** Lowest snq per bucket by bucket index; a bucket with no readings is absent. */
    val lows: Map<Int, Int> = emptyMap(),
) {
    /** The bucket index a time falls in, as a fraction, for drawing events against the series. */
    fun position(atMs: Long): Double = (atMs - startMs).toDouble() / bucketMs

    operator fun plus(reading: Pair<Long, SignalSample>): TimelineSeries {
        val (atMs, sample) = reading
        if (atMs < startMs) return this
        var series = this
        var index = ((atMs - startMs) / bucketMs).toInt()
        while (index >= series.maxBuckets) {
            series = series.halved()
            index = ((atMs - startMs) / series.bucketMs).toInt()
        }
        val snq = if (sample.locked) sample.snq else 0
        val low = series.lows[index]?.let { minOf(it, snq) } ?: snq
        return series.copy(lows = series.lows + (index to low))
    }

    private fun halved(): TimelineSeries {
        val joined = HashMap<Int, Int>()
        for ((i, v) in lows) joined.merge(i / 2, v, ::minOf)
        return copy(bucketMs = bucketMs * 2, lows = joined)
    }

    companion object {
        const val DEFAULT_MAX_BUCKETS = 720

        /**
         * A series for a session planned to last [plannedMs] (null: until stopped), sized so the whole plan fits in
         * about [DEFAULT_MAX_BUCKETS] / 1.2 buckets without doubling, and never finer than [pollIntervalMs].
         */
        fun forPlan(startMs: Long, plannedMs: Long?, pollIntervalMs: Long): TimelineSeries {
            val target = DEFAULT_MAX_BUCKETS * 5 / 6
            val width = plannedMs?.let { (it + target - 1) / target } ?: (pollIntervalMs * 5)
            return TimelineSeries(startMs, maxOf(width, pollIntervalMs, 1))
        }
    }
}

/** What a dip notification says: [dips] in the last hour, on [channels] (guide numbers, most dips first). */
data class DipAlert(val dips: Int, val channels: List<String>)

/**
 * Rate limit for dip notifications (SPEC "Dip detection"): at most one per [minIntervalMs], summing the dips that
 * ended in the last [windowMs].
 */
class DipAlerts(private val minIntervalMs: Long = 10 * 60_000L, private val windowMs: Long = 60 * 60_000L) {
    private val recent = ArrayDeque<SignalEvent>()
    private var lastAlertMs: Long? = null

    /** A finished event at [nowMs]; an alert when one is due. */
    fun onEvent(event: SignalEvent, nowMs: Long): DipAlert? {
        if (!event.isDip) return null
        recent += event
        while (recent.isNotEmpty() && recent.first().endMs < nowMs - windowMs) recent.removeFirst()
        val last = lastAlertMs
        if (last != null && nowMs - last < minIntervalMs) return null
        lastAlertMs = nowMs
        val channels = recent.mapNotNull { it.guideNumber }.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }.map { it.key }
        return DipAlert(recent.size, channels)
    }
}

/** How much a monitor session will store (SPEC Screens › Monitor setup: "About 43,000 readings, 3 MB"). */
data class MonitorEstimate(val readings: Long, val bytes: Long) {
    companion object {
        /** A reading row with its index entry: SPEC "Data model" puts 43,000 of them at about 3 MB. */
        const val BYTES_PER_READING = 70L

        fun of(durationMs: Long, pollIntervalMs: Long): MonitorEstimate {
            val readings = durationMs / pollIntervalMs.coerceAtLeast(1)
            return MonitorEstimate(readings, readings * BYTES_PER_READING)
        }
    }
}
