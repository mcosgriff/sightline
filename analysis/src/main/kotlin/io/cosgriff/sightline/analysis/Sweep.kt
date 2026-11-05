package io.cosgriff.sightline.analysis

/**
 * One channel's dwell in a sweep, summarised (SPEC Screens › Sweep). snq figures are over the locked readings
 * only; they are null when the channel never locked.
 */
data class SweptChannel(
    val guideNumber: String,
    val readings: Int,
    val lockedReadings: Int,
    val minSnq: Int?,
    val medianSnq: Int?,
    val maxSnq: Int?,
    val medianSs: Int?,
    val medianSeq: Int?,
    /** Null when nothing was read (the channel couldn't be tuned). */
    val health: Health?,
) {
    val measured get() = readings > 0

    companion object {
        /**
         * Summarises [samples], in the order read. Never locked is No lock; losing the lock at any point in the
         * dwell is Poor (a dropout); otherwise the health of the median reading.
         */
        fun of(guideNumber: String, samples: List<SignalSample>, thresholds: Thresholds): SweptChannel {
            val locked = samples.filter { it.locked }
            val health = when {
                samples.isEmpty() -> null
                locked.isEmpty() -> Health.NO_LOCK
                locked.size < samples.size -> Health.POOR
                else -> thresholds.assess(
                    SignalSample(true, median(locked.map { it.ss })!!, median(locked.map { it.snq })!!, median(locked.map { it.seq })!!),
                ).health
            }
            return SweptChannel(
                guideNumber = guideNumber,
                readings = samples.size,
                lockedReadings = locked.size,
                minSnq = locked.minOfOrNull { it.snq },
                medianSnq = median(locked.map { it.snq }),
                maxSnq = locked.maxOfOrNull { it.snq },
                medianSs = median(locked.map { it.ss }),
                medianSeq = median(locked.map { it.seq }),
                health = health,
            )
        }
    }
}

/** The lower median, so it is always a value that was actually read. Null for none. */
fun median(values: List<Int>): Int? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    return sorted[(sorted.size - 1) / 2]
}

/** A whole sweep's channels with the counts the summary cards show. */
data class SweepSummary(val channels: List<SweptChannel>) {
    val good get() = channels.count { it.health == Health.GOOD }
    val marginal get() = channels.count { it.health == Health.MARGINAL }

    /** Poor counts channels that never locked too: both need fixing, and the row says which. */
    val poor get() = channels.count { it.health == Health.POOR || it.health == Health.NO_LOCK }

    /** Measured channels worst first (No lock, then by median snq rising), then those that couldn't be tuned. */
    val worstFirst: List<SweptChannel>
        get() = channels.sortedWith(
            compareBy<SweptChannel> { !it.measured }
                .thenByDescending { it.health?.ordinal ?: -1 }
                .thenBy { it.medianSnq ?: -1 }
                .thenBy { it.guideNumber },
        )
}

/** How one channel changed between two sweeps. */
enum class ChannelChange { BETTER, WORSE, SAME, NEW, GONE, NOT_COMPARABLE }

data class ChannelDelta(
    val guideNumber: String,
    val before: SweptChannel?,
    val after: SweptChannel?,
    /** after − before median snq, when both locked. */
    val snqDelta: Int?,
    val change: ChannelChange,
)

/**
 * Two sweeps of one device compared channel by channel (SPEC: "vs sweep on Oct 28: 5 better · 1 worse"). A change
 * in median snq of at least [minDelta] points counts; less is noise. Gaining or losing lock always counts.
 */
data class SweepComparison(val deltas: List<ChannelDelta>) {
    val better get() = deltas.count { it.change == ChannelChange.BETTER }
    val worse get() = deltas.count { it.change == ChannelChange.WORSE }

    companion object {
        const val DEFAULT_MIN_DELTA = 3

        fun of(before: List<SweptChannel>, after: List<SweptChannel>, minDelta: Int = DEFAULT_MIN_DELTA): SweepComparison {
            val old = before.associateBy { it.guideNumber }
            val new = after.associateBy { it.guideNumber }
            val order = after.map { it.guideNumber } + before.map { it.guideNumber }.filter { it !in new }
            return SweepComparison(order.map { guide -> delta(guide, old[guide], new[guide], minDelta) })
        }

        private fun delta(guide: String, before: SweptChannel?, after: SweptChannel?, minDelta: Int): ChannelDelta {
            val b = before?.takeIf { it.measured }
            val a = after?.takeIf { it.measured }
            val bSnq = b?.medianSnq
            val aSnq = a?.medianSnq
            val snqDelta = if (bSnq != null && aSnq != null) aSnq - bSnq else null
            val change = when {
                before == null -> ChannelChange.NEW
                after == null -> ChannelChange.GONE
                a == null || b == null -> ChannelChange.NOT_COMPARABLE
                snqDelta != null -> when {
                    snqDelta >= minDelta -> ChannelChange.BETTER
                    snqDelta <= -minDelta -> ChannelChange.WORSE
                    else -> ChannelChange.SAME
                }
                aSnq != null -> ChannelChange.BETTER // locks now, didn't before
                bSnq != null -> ChannelChange.WORSE
                else -> ChannelChange.SAME // no lock either time
            }
            return ChannelDelta(guide, before, after, snqDelta, change)
        }
    }
}
