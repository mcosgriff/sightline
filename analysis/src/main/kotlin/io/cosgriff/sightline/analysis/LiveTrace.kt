package io.cosgriff.sightline.analysis

/** A sample with its time, UTC epoch milliseconds. */
data class TimedSample(val atMs: Long, val sample: SignalSample)

/** The best value of each metric seen while locked. */
data class Best(val ss: Int, val snq: Int, val seq: Int)

/**
 * What Live keeps while it watches a channel: the last [windowMs] of samples for the sparkline, and the session
 * best for the gauges' ghost markers. Immutable; [plus] returns the next trace. Samples older than the window,
 * or from before a clock jump backwards, drop out.
 */
data class LiveTrace(
    val windowMs: Long = 60_000,
    val recent: List<TimedSample> = emptyList(),
    val best: Best? = null,
) {
    val latest: TimedSample? get() = recent.lastOrNull()

    operator fun plus(sample: TimedSample): LiveTrace {
        val kept = recent.filter { it.atMs > sample.atMs - windowMs && it.atMs <= sample.atMs }
        val s = sample.sample
        val nextBest = if (!s.locked) best
        else best?.let { Best(maxOf(it.ss, s.ss), maxOf(it.snq, s.snq), maxOf(it.seq, s.seq)) } ?: Best(s.ss, s.snq, s.seq)
        return copy(recent = kept + sample, best = nextBest)
    }
}
