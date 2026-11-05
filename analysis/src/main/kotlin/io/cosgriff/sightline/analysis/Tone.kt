package io.cosgriff.sightline.analysis

/** What the aiming tone plays for one reading: a pitch, and whether it stutters. Silent with no lock. */
data class ToneSpec(val hz: Double, val stutter: Boolean)

/**
 * Tone mode (SPEC "Feedback that doesn't need eyes"): pitch rises with snq from [MIN_HZ] at 0 to [MAX_HZ] at 100,
 * and the tone stutters while seq is under 100 (any lost symbol is a visible error).
 */
object Tone {
    const val MIN_HZ = 200.0
    const val MAX_HZ = 1200.0

    fun of(sample: SignalSample): ToneSpec? {
        if (!sample.locked) return null
        val snq = sample.snq.coerceIn(0, 100)
        return ToneSpec(MIN_HZ + (MAX_HZ - MIN_HZ) * snq / 100.0, stutter = sample.seq < 100)
    }
}
