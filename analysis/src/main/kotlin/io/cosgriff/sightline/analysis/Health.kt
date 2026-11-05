package io.cosgriff.sightline.analysis

/** One reading's numbers, whatever they came from. With [locked] false the numbers mean nothing. */
data class SignalSample(val locked: Boolean, val ss: Int, val snq: Int, val seq: Int)

/** Good, Marginal, Poor or No lock (GLOSSARY "Health"). Ordered best first. */
enum class Health { GOOD, MARGINAL, POOR, NO_LOCK }

/**
 * The bands from SPEC "Signal thresholds", editable in Settings. snq: good ≥ [snqGood], marginal ≥ [snqMarginal].
 * seq: good ≥ [seqGood] (100: any drop is a visible error), marginal ≥ [seqMarginal]. ss is neutral for overall
 * health; it only colours its own gauge and explains a problem: good from [ssGood] to below [ssOverload], poor
 * below [ssMarginal], marginal in between or at [ssOverload] and above (overload).
 */
data class Thresholds(
    val snqGood: Int = 80,
    val snqMarginal: Int = 60,
    val seqGood: Int = 100,
    val seqMarginal: Int = 90,
    val ssGood: Int = 40,
    val ssMarginal: Int = 25,
    val ssOverload: Int = 95,
) {
    fun snq(value: Int) = band(value, snqGood, snqMarginal)

    fun seq(value: Int) = band(value, seqGood, seqMarginal)

    fun ss(value: Int) = when {
        value >= ssOverload -> Health.MARGINAL
        value >= ssGood -> Health.GOOD
        value >= ssMarginal -> Health.MARGINAL
        else -> Health.POOR
    }

    private fun band(value: Int, good: Int, marginal: Int) = when {
        value >= good -> Health.GOOD
        value >= marginal -> Health.MARGINAL
        else -> Health.POOR
    }

    companion object {
        val DEFAULT = Thresholds()
    }
}

/** Why a reading has the health it has: what Live's banner says in one sentence. */
enum class HealthReason {
    /** All good. */
    CLEAN,

    /** All good, but strength is at the overload line: an amplifier or a nearby tower could tip it over. */
    GOOD_NEAR_OVERLOAD,

    /** Not good with strength at the overload line: the tuner is likely overloaded, or multipath. */
    OVERLOAD,

    /** Not good with weak strength: too little signal reaches the tuner. */
    WEAK,

    /** Quality is fine but symbols are being lost: errors on screen. */
    SYMBOL_ERRORS,

    /** Enough strength but poor quality: noise, interference or multipath. */
    LOW_QUALITY,

    NO_LOCK,
}

data class Assessment(val health: Health, val reason: HealthReason)

/** The overall health of one reading: the worse of snq and seq, explained using ss. */
fun Thresholds.assess(sample: SignalSample): Assessment {
    if (!sample.locked) return Assessment(Health.NO_LOCK, HealthReason.NO_LOCK)
    val snqHealth = snq(sample.snq)
    val health = maxOf(snqHealth, seq(sample.seq))
    val overload = sample.ss >= ssOverload
    val reason = when {
        health == Health.GOOD -> if (overload) HealthReason.GOOD_NEAR_OVERLOAD else HealthReason.CLEAN
        overload -> HealthReason.OVERLOAD
        sample.ss < ssGood -> HealthReason.WEAK
        snqHealth == Health.GOOD -> HealthReason.SYMBOL_ERRORS
        else -> HealthReason.LOW_QUALITY
    }
    return Assessment(health, reason)
}
