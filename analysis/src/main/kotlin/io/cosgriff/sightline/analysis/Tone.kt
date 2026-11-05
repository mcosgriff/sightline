package io.cosgriff.sightline.analysis

/** What the aiming tone plays for one reading: a pitch, and whether it stutters. Silent with no lock. */
data class ToneSpec(val hz: Double, val stutter: Boolean)

/** The pitches the tone spans, low at snq 0 and high at snq 100 (Settings › Tone range). */
data class ToneRange(val lowHz: Int = Tone.MIN_HZ, val highHz: Int = Tone.MAX_HZ)

/**
 * Tone mode (SPEC "Feedback that doesn't need eyes"): pitch rises with snq across the [ToneRange] (200 Hz at 0 to
 * 1200 Hz at 100 by default), and the tone stutters while seq is under 100 (any lost symbol is a visible error).
 */
object Tone {
    const val MIN_HZ = 200
    const val MAX_HZ = 1200

    fun of(sample: SignalSample, range: ToneRange = ToneRange()): ToneSpec? {
        if (!sample.locked) return null
        val snq = sample.snq.coerceIn(0, 100)
        return ToneSpec(range.lowHz + (range.highHz - range.lowHz) * snq / 100.0, stutter = sample.seq < 100)
    }
}
