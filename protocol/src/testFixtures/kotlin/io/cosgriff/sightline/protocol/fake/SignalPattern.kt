package io.cosgriff.sightline.protocol.fake

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/** What a fake channel reports at a moment: locked or not, and ss, snq and seq. */
data class Signal(val locked: Boolean, val ss: Int, val snq: Int, val seq: Int) {
    companion object {
        val NONE = Signal(false, 0, 0, 0)
    }
}

/**
 * A scripted signal over time (SPEC "Testing": steady, slow fade, wind flutter, hard dropouts). [at] takes
 * milliseconds since the channel was tuned and must be deterministic, so tests can assert exact events.
 */
fun interface SignalPattern {
    fun at(elapsedMs: Long): Signal

    companion object {
        /** A strong, clean channel with ±1 of jitter. */
        fun steady(ss: Int = 92, snq: Int = 88): SignalPattern = SignalPattern { t ->
            val wobble = jitter(t / 1000, 1)
            Signal(true, ss, (snq + wobble).coerceIn(0, 100), 100)
        }

        /** snq sinks from [from] to [to] over [overMs] and stays there; seq falls once snq is under 60. */
        fun slowFade(from: Int = 85, to: Int = 45, overMs: Long = 30 * 60_000L): SignalPattern = SignalPattern { t ->
            val f = (t.toDouble() / overMs).coerceIn(0.0, 1.0)
            val snq = (from + (to - from) * f).roundToInt()
            Signal(true, 80, snq, if (snq < 60) (100 - (60 - snq) * 3).coerceAtLeast(0) else 100)
        }

        /**
         * Wind flutter: snq swings around [base] with gusts every [periodMs]; at the bottom of a gust it goes under
         * the poor threshold and seq drops, for a few seconds.
         */
        fun flutter(base: Int = 72, depth: Int = 25, periodMs: Long = 45_000L): SignalPattern = SignalPattern { t ->
            val phase = (t % periodMs).toDouble() / periodMs
            val gust = sin(phase * 2 * PI)
            val snq = (base - (depth * abs(gust) * if (gust < 0) 1.0 else 0.3)).roundToInt() + jitter(t / 1000, 2)
            val seq = if (snq < 55) (100 - (55 - snq) * 6).coerceIn(0, 100) else 100
            Signal(true, 78, snq.coerceIn(0, 100), seq)
        }

        /** Good signal with a hard dropout (no lock) of [lengthMs] every [everyMs]. */
        fun dropouts(everyMs: Long = 120_000L, lengthMs: Long = 6_000L, good: SignalPattern = steady(85, 80)) =
            SignalPattern { t -> if (t % everyMs >= everyMs - lengthMs) Signal.NONE else good.at(t) }

        /** Never locks: a channel the antenna can't see. */
        val noSignal: SignalPattern = SignalPattern { Signal(false, 30, 0, 0) }

        /** A deterministic offset in -[amp]..[amp] for second [second]. */
        private fun jitter(second: Long, amp: Int): Int {
            var x = second * 0x9E3779B97F4A7C15uL.toLong()
            x = (x xor (x ushr 31)) * 0xBF58476D1CE4E5B9uL.toLong()
            return ((x ushr 33) % (2 * amp + 1)).toInt() - amp
        }
    }
}
