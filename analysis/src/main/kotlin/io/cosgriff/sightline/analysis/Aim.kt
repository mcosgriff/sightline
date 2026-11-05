package io.cosgriff.sightline.analysis

import kotlin.math.floor

/** The best snq seen so far and where the phone pointed then. [headingDeg] is null when there was no compass. */
data class AimPeak(val snq: Int, val headingDeg: Int?)

/** What a reading on Aim asks the phone to do without eyes (SPEC "Feedback that doesn't need eyes"). */
enum class AimEvent {
    /** snq beat the session peak: a short tick. */
    NEW_PEAK,

    /** Was locked, now isn't: a double buzz. */
    LOCK_LOST,
}

/**
 * Aim's memory for one channel (SPEC Screens › Aim): the session peak with its heading, and the best snq seen in
 * each [binDeg]-degree slice of the compass for the polar plot. Only locked readings count. Readings without a
 * heading (no compass) move the peak but not the plot.
 */
data class AimTrace(
    val peak: AimPeak? = null,
    /** Best snq by bin index; bin `i` covers `i * binDeg` up to `(i + 1) * binDeg`. */
    val bins: Map<Int, Int> = emptyMap(),
    val wasLocked: Boolean = false,
    val binDeg: Int = DEFAULT_BIN_DEG,
) {
    init {
        require(360 % binDeg == 0) { "binDeg must divide 360" }
    }

    val binCount get() = 360 / binDeg

    /** The plot's points in compass order: each bin's centre heading and its best snq. */
    val points: List<AimPoint>
        get() = bins.toSortedMap().map { (bin, snq) -> AimPoint(bin * binDeg + binDeg / 2.0, snq) }

    /** Adds one reading taken at [headingDeg]; returns the new trace and what to signal, if anything. */
    fun add(sample: SignalSample, headingDeg: Double?): Pair<AimTrace, AimEvent?> {
        if (!sample.locked) {
            return copy(wasLocked = false) to if (wasLocked) AimEvent.LOCK_LOST else null
        }
        val heading = headingDeg?.let(::normalizeDegrees)
        val newPeak = peak == null || sample.snq > peak.snq
        val bins = if (heading == null) bins else {
            val bin = floor(heading / binDeg).toInt().coerceIn(0, binCount - 1)
            if ((bins[bin] ?: -1) >= sample.snq) bins else bins + (bin to sample.snq)
        }
        val next = copy(
            peak = if (newPeak) AimPeak(sample.snq, heading?.let { roundDegrees(it) }) else peak,
            bins = bins,
            wasLocked = true,
        )
        // The first locked reading sets the peak but isn't news: no tick for merely tuning in.
        return next to if (newPeak && peak != null) AimEvent.NEW_PEAK else null
    }

    /** Forgets the peak and the plot, keeping whether the tuner is locked (Reset peak). */
    fun reset() = AimTrace(wasLocked = wasLocked, binDeg = binDeg)

    companion object {
        const val DEFAULT_BIN_DEG = 5
    }
}

data class AimPoint(val headingDeg: Double, val snq: Int)
