package io.cosgriff.sightline.analysis

import io.cosgriff.sightline.protocol.Band
import java.time.Instant

/** How sure a finding is (SPEC "Diagnosis engine"): the badge on its card. */
enum class Strength { LIKELY, POSSIBLE }

/** A channel's medians over its locked readings, as overload and weak-signal evidence quotes them. */
data class ChannelLevel(val guideNumber: String, val medianSs: Int, val medianSnq: Int)

/** A stretch of hours in which [dips] of a weather finding's dips fell: "19 of 23 dips fell on Nov 3 between noon and 6 PM". */
data class DipStretch(val startMs: Long, val endMs: Long, val dips: Int)

/**
 * One finding of the diagnosis engine: a pattern from the SPEC table, with the numbers that made it fire. `:app`
 * words it, its evidence and its fix. [score] is how far past its firing line the evidence is (1 = just at it),
 * for sorting findings of the same [strength].
 */
sealed interface Finding {
    val strength: Strength
    val score: Double

    /** Median ss ≥ 95 with median snq < 70 on [channels]. */
    data class Overload(val channels: List<ChannelLevel>, override val strength: Strength, override val score: Double) : Finding

    /** Median ss < 35 with median snq < 60 on [channels]. */
    data class WeakSignal(val channels: List<ChannelLevel>, override val strength: Strength, override val score: Double) : Finding

    /**
     * [guideNumber]'s median snq is 20+ under the median of the [others]' medians. [everyHour]: it was lower in every
     * clock hour both were read.
     */
    data class OneChannelBad(
        val guideNumber: String,
        val medianSnq: Int,
        val othersMedianSnq: Int,
        val others: List<String>,
        val everyHour: Boolean,
        override val strength: Strength,
        override val score: Double,
    ) : Finding

    /** The VHF channels' median snq is 20+ under the UHF channels'. */
    data class VhfWeak(
        val vhfChannels: List<String>,
        val vhfMedianSnq: Int,
        val uhfMedianSnq: Int,
        override val strength: Strength,
        override val score: Double,
    ) : Finding

    /** Dips on RF 30–36 at [ratio]× (null: lower channels had none) the rate of the lower channels. */
    data class UpperUhfInterference(
        val upperChannels: List<String>,
        val upperDips: Int,
        val lowerDips: Int,
        val ratio: Double?,
        override val strength: Strength,
        override val score: Double,
    ) : Finding

    /** [share] (0..1) of dip time fell between [startHour] and [startHour] + 4 local time, on [days] different days. */
    data class TimeOfDay(
        val startHour: Int,
        val share: Double,
        val days: Int,
        override val strength: Strength,
        override val score: Double,
    ) : Finding

    /**
     * Wind, rain or heat: the dip rate in [cause]'s hours was [ratio]× the rate in its calm hours (null: the calm
     * hours had no dips). [line] is what made an hour count: gusts over it (km/h), precipitation (mm), or the
     * coolest temperature of the hottest hours (°C). [dips] of the session's [totalDips] fell in those hours, and
     * [peak] is the stretch that held most of the session's dips, when one did.
     */
    data class FollowsWeather(
        val cause: WeatherCause,
        val ratio: Double?,
        val line: Double,
        val dips: Int,
        val totalDips: Int,
        val peak: DipStretch?,
        override val strength: Strength,
        override val score: Double,
    ) : Finding

    /** [sharedDips] of [totalDips] started within 2 s of a dip on every other channel read at the same time. */
    data class CommonPath(val sharedDips: Int, val totalDips: Int, override val strength: Strength, override val score: Double) : Finding

    /** [guideNumber] dipped [dips] times on [tuner], [otherDips] times on [otherTuners] over the same kind of time. */
    data class OneTuner(
        val guideNumber: String,
        val tuner: Int,
        val dips: Int,
        val otherTuners: List<Int>,
        val otherDips: Int,
        override val strength: Strength,
        override val score: Double,
    ) : Finding

    /** [count] of [totalDips] dips were symbol errors with snq still ≥ the good line; seq fell to [lowestSeq]. */
    data class ImpulseNoise(val count: Int, val totalDips: Int, val lowestSeq: Int?, override val strength: Strength, override val score: Double) : Finding

    /** snq swung 20+ points within 10 s [swings] times, on [channels]. */
    data class Flutter(val swings: Int, val channels: List<String>, override val strength: Strength, override val score: Double) : Finding

    /** The device stopped answering [count] times, for [totalMs] in all, against [dips] dips. */
    data class NotRf(val count: Int, val totalMs: Long, val dips: Int, override val strength: Strength, override val score: Double) : Finding
}

enum class WeatherCause { WIND, RAIN, HEAT }

/** One row of the SPEC table. Returns its finding, or null when the session doesn't show the pattern. */
fun interface DiagnosisRule {
    fun evaluate(session: Diagnosable): Finding?
}

/**
 * A session prepared for the rules: [SessionData] plus what several rules need, computed once. Rule numbers are
 * in `docs/rules/diagnosis.md`.
 */
class Diagnosable(val data: SessionData, val thresholds: Thresholds) {
    val dips: List<SignalEvent> = data.events.filter { it.isDip }

    /** Each channel's median snq over its locked readings; a channel read but never locked counts as 0. */
    val channelMedians: Map<String, Int> = data.byChannel.mapValues { (_, rs) -> median(rs.filter { it.sample.locked }.map { it.sample.snq }) ?: 0 }

    /** How long each channel was read, ms: readings times the poll interval. */
    fun exposureMs(readings: Int): Long = readings * data.pollIntervalMs

    fun hourOf(atMs: Long) = Math.floorDiv(atMs, WeatherHour.HOUR_MS) * WeatherHour.HOUR_MS

    /** Readings per clock hour (UTC epoch ms of its start). */
    val readingsPerHour: Map<Long, Int> by lazy { data.readings.groupingBy { hourOf(it.atMs) }.eachCount() }

    /** The tuner a dip happened on: that of the channel's reading nearest its start. */
    fun tunerOf(event: SignalEvent): Int? {
        val rs = data.byChannel[event.guideNumber ?: return null] ?: return null
        if (rs.isEmpty()) return null
        var i = rs.binarySearch { it.atMs.compareTo(event.startMs) }
        if (i < 0) i = (-i - 1).coerceAtMost(rs.lastIndex)
        return rs[i].tuner
    }
}

/**
 * The diagnosis engine (SPEC "Diagnosis engine"): every rule over the session, findings sorted Likely first, then
 * by how far past its line each one is. An empty list means no pattern found; the screen says so with the summary
 * numbers.
 */
object Diagnosis {
    val RULES: List<DiagnosisRule> = listOf(
        OverloadRule, WeakSignalRule, OneChannelBadRule, VhfWeakRule, UpperUhfRule, TimeOfDayRule,
        WindRule, RainRule, HeatRule, CommonPathRule, OneTunerRule, ImpulseNoiseRule, FlutterRule, NotRfRule,
    )

    /** Scores past this count as equal, so a pile of evidence for one rule can't bury a clearer cause. */
    const val MAX_SCORE = 5.0

    fun run(data: SessionData, thresholds: Thresholds, rules: List<DiagnosisRule> = RULES): List<Finding> {
        val session = Diagnosable(data, thresholds)
        // Stable: equal findings keep the rules' order, which puts causes (wind, rain) before mechanisms (flutter).
        return rules.mapNotNull { it.evaluate(session) }
            .sortedWith(compareBy<Finding> { it.strength.ordinal }.thenByDescending { it.score.coerceAtMost(MAX_SCORE) })
    }
}

/** At least this much reading time on each side of a comparison, so a rate means something. */
internal const val MIN_EXPOSURE_MS = WeatherHour.HOUR_MS

/** Dip rates compared: at least this many times higher. */
internal const val RATE_RATIO = 3.0

/** Fewest dips in the worse group for a rate rule to fire, and for it to be Likely. */
internal const val MIN_DIPS = 3
internal const val LIKELY_DIPS = 5

/** Median snq gaps between channels or bands: fires at this, Likely at [LIKELY_GAP]. */
internal const val SNQ_GAP = 20
internal const val LIKELY_GAP = 30

private fun levels(s: Diagnosable): List<ChannelLevel> = s.data.byChannel.mapNotNull { (g, rs) ->
    val locked = rs.filter { it.sample.locked }
    if (locked.isEmpty()) null else ChannelLevel(g, median(locked.map { it.sample.ss })!!, median(locked.map { it.sample.snq })!!)
}

/** Overload: median ss ≥ 95 and median snq < 70. Likely when snq is in the poor band too. */
object OverloadRule : DiagnosisRule {
    const val SS = 95
    const val SNQ = 70

    override fun evaluate(session: Diagnosable): Finding? {
        val hit = levels(session).filter { it.medianSs >= SS && it.medianSnq < SNQ }
        if (hit.isEmpty()) return null
        val likely = hit.any { it.medianSnq < session.thresholds.snqMarginal }
        return Finding.Overload(hit, if (likely) Strength.LIKELY else Strength.POSSIBLE, hit.size.toDouble() / session.data.byChannel.size + 1)
    }
}

/** Weak signal: median ss < 35 and median snq < 60. Likely when ss is in the poor band (< 25). */
object WeakSignalRule : DiagnosisRule {
    const val SS = 35
    const val SNQ = 60

    override fun evaluate(session: Diagnosable): Finding? {
        val hit = levels(session).filter { it.medianSs < SS && it.medianSnq < SNQ }
        if (hit.isEmpty()) return null
        val likely = hit.any { it.medianSs < session.thresholds.ssMarginal }
        return Finding.WeakSignal(hit, if (likely) Strength.LIKELY else Strength.POSSIBLE, hit.size.toDouble() / session.data.byChannel.size + 1)
    }
}

/**
 * One channel bad: a channel's median snq 20+ under the median of the others' medians. Only fires when the low
 * channels are half or fewer (one of two counts); otherwise it isn't one channel. Likely at 30+.
 */
object OneChannelBadRule : DiagnosisRule {
    override fun evaluate(session: Diagnosable): Finding? {
        val medians = session.channelMedians
        if (medians.size < 2) return null
        val gaps = medians.mapValues { (g, m) -> median(medians.filterKeys { it != g }.values.toList())!! - m }
        val low = gaps.filterValues { it >= SNQ_GAP }
        if (low.isEmpty() || low.size * 2 > medians.size) return null
        val (guide, gap) = low.maxBy { it.value }
        val others = medians.keys.filter { it != guide }
        return Finding.OneChannelBad(
            guideNumber = guide,
            medianSnq = medians.getValue(guide),
            othersMedianSnq = medians.getValue(guide) + gap,
            others = others,
            everyHour = lowerEveryHour(session, guide, others),
            strength = if (gap >= LIKELY_GAP) Strength.LIKELY else Strength.POSSIBLE,
            score = gap.toDouble() / SNQ_GAP,
        )
    }

    /** In every clock hour with readings of [guide] and the others, its median was under theirs. */
    private fun lowerEveryHour(s: Diagnosable, guide: String, others: List<String>): Boolean {
        fun perHour(rs: List<LoggedReading>) = rs.groupBy { s.hourOf(it.atMs) }.mapValues { (_, h) -> median(h.map { if (it.sample.locked) it.sample.snq else 0 })!! }
        val mine = perHour(s.data.byChannel.getValue(guide))
        val theirs = perHour(others.flatMap { s.data.byChannel.getValue(it) })
        val shared = mine.keys intersect theirs.keys
        return shared.isNotEmpty() && shared.all { mine.getValue(it) < theirs.getValue(it) }
    }
}

/** VHF weak: the VHF channels' median snq 20+ under the UHF channels' (medians of channel medians). Likely at 30+. */
object VhfWeakRule : DiagnosisRule {
    override fun evaluate(session: Diagnosable): Finding? {
        val bands = session.data.channels
        val vhf = session.channelMedians.filterKeys { bands[it]?.band.let { b -> b == Band.VHF_LO || b == Band.VHF_HI } }
        val uhf = session.channelMedians.filterKeys { bands[it]?.band == Band.UHF }
        if (vhf.isEmpty() || uhf.isEmpty()) return null
        val v = median(vhf.values.toList())!!
        val u = median(uhf.values.toList())!!
        val gap = u - v
        if (gap < SNQ_GAP) return null
        return Finding.VhfWeak(vhf.keys.toList(), v, u, if (gap >= LIKELY_GAP) Strength.LIKELY else Strength.POSSIBLE, gap.toDouble() / SNQ_GAP)
    }
}

/**
 * Upper UHF interference: dips per hour read on RF 30–36 at least 3× those on lower channels, with 3+ dips there.
 * Likely with 5+ dips there and none lower down.
 */
object UpperUhfRule : DiagnosisRule {
    val UPPER = 30..36

    override fun evaluate(session: Diagnosable): Finding? {
        val rf = session.data.channels
        val upper = session.data.guideNumbers.filter { rf[it]?.rfChannel in UPPER }
        val lower = session.data.guideNumbers.filter { rf[it]?.rfChannel.let { c -> c != null && c < UPPER.first } }
        if (upper.isEmpty() || lower.isEmpty()) return null
        val upperMs = session.exposureMs(upper.sumOf { session.data.byChannel.getValue(it).size })
        val lowerMs = session.exposureMs(lower.sumOf { session.data.byChannel.getValue(it).size })
        if (upperMs < MIN_EXPOSURE_MS || lowerMs < MIN_EXPOSURE_MS) return null
        val upperDips = session.dips.count { it.guideNumber in upper }
        val lowerDips = session.dips.count { it.guideNumber in lower }
        val ratio = rateRatio(upperDips, upperMs, lowerDips, lowerMs)
        if (upperDips < MIN_DIPS || (ratio != null && ratio < RATE_RATIO)) return null
        val likely = upperDips >= LIKELY_DIPS && lowerDips == 0
        return Finding.UpperUhfInterference(upper, upperDips, lowerDips, ratio, if (likely) Strength.LIKELY else Strength.POSSIBLE, score(ratio))
    }
}

/**
 * Time of day: over 50% of dip time starts in one 4-hour window of local time, with dips in it on 2+ days of a
 * session long enough to see that window twice. Likely on 3+ days.
 */
object TimeOfDayRule : DiagnosisRule {
    const val WINDOW_HOURS = 4
    const val SHARE = 0.5

    override fun evaluate(session: Diagnosable): Finding? {
        val data = session.data
        if (data.endMs - data.startMs < (24 + WINDOW_HOURS) * WeatherHour.HOUR_MS) return null
        val dips = session.dips
        if (dips.size < MIN_DIPS) return null
        val total = dips.sumOf { it.durationMs.coerceAtLeast(1) }.toDouble()
        fun inWindow(e: SignalEvent, start: Int) = Math.floorMod(data.hourOfDay(e.startMs) - start, 24) < WINDOW_HOURS
        val best = (0 until 24).maxBy { h -> dips.filter { inWindow(it, h) }.sumOf { it.durationMs.coerceAtLeast(1) } }
        val inside = dips.filter { inWindow(it, best) }
        val share = inside.sumOf { it.durationMs.coerceAtLeast(1) } / total
        val days = inside.map { Instant.ofEpochMilli(it.startMs).atZone(data.zone).toLocalDate() }.distinct().size
        if (share <= SHARE || days < 2) return null
        return Finding.TimeOfDay(best, share, days, if (days >= 3) Strength.LIKELY else Strength.POSSIBLE, share / SHARE)
    }
}

/** Dips per ms in one group over another; null when the other had none (any dips are infinitely more). */
private fun rateRatio(dips: Int, ms: Long, baseDips: Int, baseMs: Long): Double? =
    if (baseDips == 0) null else (dips.toDouble() / ms) / (baseDips.toDouble() / baseMs)

/** How far past [RATE_RATIO] a ratio is; no base dips counts as far past. */
private fun score(ratio: Double?) = ((ratio ?: (RATE_RATIO * 5)) / RATE_RATIO).coerceAtMost(5.0)

/**
 * The shared test of the weather rules: dips starting in [condition] hours against dips in [calm] hours, per hour
 * read. Fires with 3+ dips in the condition hours and a rate 3× the calm one (or calm hours without dips), each
 * side read for an hour or more. Likely with 5+ dips.
 */
internal fun weatherFinding(s: Diagnosable, cause: WeatherCause, line: Double, condition: Set<Long>, calm: Set<Long>): Finding? {
    val condMs = s.exposureMs(condition.sumOf { s.readingsPerHour[it] ?: 0 })
    val calmMs = s.exposureMs(calm.sumOf { s.readingsPerHour[it] ?: 0 })
    if (condMs < MIN_EXPOSURE_MS || calmMs < MIN_EXPOSURE_MS) return null
    val condDips = s.dips.filter { s.hourOf(it.startMs) in condition }
    val calmDips = s.dips.count { s.hourOf(it.startMs) in calm }
    val ratio = rateRatio(condDips.size, condMs, calmDips, calmMs)
    if (condDips.size < MIN_DIPS || (ratio != null && ratio < RATE_RATIO)) return null
    return Finding.FollowsWeather(
        cause = cause,
        ratio = ratio,
        line = line,
        dips = condDips.size,
        totalDips = s.dips.size,
        peak = peak(s, condition, condDips),
        strength = if (condDips.size >= LIKELY_DIPS) Strength.LIKELY else Strength.POSSIBLE,
        score = score(ratio),
    )
}

/** The run of consecutive [hours] holding the most of [dips], when it holds at least half of all the session's dips. */
private fun peak(s: Diagnosable, hours: Set<Long>, dips: List<SignalEvent>): DipStretch? {
    val runs = mutableListOf<MutableList<Long>>()
    for (h in hours.sorted()) {
        val last = runs.lastOrNull()
        if (last != null && h - last.last() == WeatherHour.HOUR_MS) last += h else runs += mutableListOf(h)
    }
    val best = runs.map { run -> run to dips.count { s.hourOf(it.startMs) in run } }.maxByOrNull { it.second } ?: return null
    if (best.second * 2 < s.dips.size) return null
    return DipStretch(best.first.first(), best.first.last() + WeatherHour.HOUR_MS, best.second)
}

/** Hours of the session that were read and have weather. */
private fun readHours(s: Diagnosable) = s.data.weather.filter { (s.readingsPerHour[it.hourMs] ?: 0) > 0 }

/** Wind: gusts over 30 km/h against gusts under 15 km/h. */
object WindRule : DiagnosisRule {
    const val GUSTY_KPH = 30.0
    const val CALM_KPH = 15.0

    override fun evaluate(session: Diagnosable): Finding? {
        val hours = readHours(session).filter { it.gustKph != null }
        return weatherFinding(
            session, WeatherCause.WIND, GUSTY_KPH,
            condition = hours.filter { it.gustKph!! > GUSTY_KPH }.map { it.hourMs }.toSet(),
            calm = hours.filter { it.gustKph!! < CALM_KPH }.map { it.hourMs }.toSet(),
        )
    }
}

/** Rain: hours with precipitation (0.1 mm or more) against dry hours. */
object RainRule : DiagnosisRule {
    const val WET_MM = 0.1

    override fun evaluate(session: Diagnosable): Finding? {
        val hours = readHours(session).filter { it.precipMm != null }
        return weatherFinding(
            session, WeatherCause.RAIN, WET_MM,
            condition = hours.filter { it.precipMm!! >= WET_MM }.map { it.hourMs }.toSet(),
            calm = hours.filter { it.precipMm!! == 0.0 }.map { it.hourMs }.toSet(),
        )
    }
}

/** Heat: the hottest quarter of the hours read against the coolest quarter. Needs 8 hours with a temperature. */
object HeatRule : DiagnosisRule {
    const val MIN_HOURS = 8

    override fun evaluate(session: Diagnosable): Finding? {
        val hours = readHours(session).filter { it.tempC != null }.sortedBy { it.tempC }
        if (hours.size < MIN_HOURS) return null
        val quarter = hours.size / 4
        val cool = hours.take(quarter)
        val hot = hours.takeLast(quarter)
        if (hot.first().tempC!! <= cool.last().tempC!!) return null
        return weatherFinding(session, WeatherCause.HEAT, hot.first().tempC!!, hot.map { it.hourMs }.toSet(), cool.map { it.hourMs }.toSet())
    }
}

/**
 * Common path: in a session reading several channels at once (more than one tuner), 80%+ of dips start within 2 s
 * of a dip on every other channel. Needs 3+ dips; Likely at 90% with 5+.
 */
object CommonPathRule : DiagnosisRule {
    const val WITHIN_MS = 2_000L
    const val SHARE = 0.8

    override fun evaluate(session: Diagnosable): Finding? {
        val data = session.data
        if (data.guideNumbers.size < 2 || data.readings.map { it.tuner }.distinct().size < 2) return null
        if (!concurrent(data)) return null
        val dips = session.dips.filter { it.guideNumber != null }
        if (dips.size < MIN_DIPS) return null
        val byGuide = dips.groupBy { it.guideNumber!! }
        val shared = dips.count { d ->
            data.guideNumbers.filter { it != d.guideNumber }.all { other ->
                byGuide[other].orEmpty().any { kotlin.math.abs(it.startMs - d.startMs) <= WITHIN_MS }
            }
        }
        val share = shared.toDouble() / dips.size
        if (share < SHARE) return null
        val likely = share >= 0.9 && dips.size >= LIKELY_DIPS
        return Finding.CommonPath(shared, dips.size, if (likely) Strength.LIKELY else Strength.POSSIBLE, share / SHARE)
    }

    /** Channels read side by side, not in turn: most 2-second stretches with readings have more than one channel. */
    private fun concurrent(data: SessionData): Boolean {
        val buckets = data.readings.groupBy { it.atMs / WITHIN_MS }.values
        return buckets.count { b -> b.map { it.guideNumber }.distinct().size > 1 } * 2 > buckets.size
    }
}

/**
 * One tuner: a channel read on several tuners, an hour or more on each, dips 3× as often on one as on the others
 * together, with 3+ dips there. Likely with 5+ and none on the others.
 */
object OneTunerRule : DiagnosisRule {
    override fun evaluate(session: Diagnosable): Finding? {
        val tunerOfDip = session.dips.associateWith { session.tunerOf(it) }
        val candidates = session.data.byChannel.mapNotNull { (guide, rs) ->
            val perTuner = rs.groupingBy { it.tuner }.eachCount().filterValues { session.exposureMs(it) >= MIN_EXPOSURE_MS }
            if (perTuner.size < 2) return@mapNotNull null
            val dips = perTuner.keys.associateWith { t -> tunerOfDip.count { (d, dt) -> d.guideNumber == guide && dt == t } }
            val (worst, worstDips) = dips.maxBy { it.value }
            val others = perTuner.keys - worst
            val otherDips = others.sumOf { dips.getValue(it) }
            val ratio = rateRatio(worstDips, session.exposureMs(perTuner.getValue(worst)), otherDips, session.exposureMs(others.sumOf { perTuner.getValue(it) }))
            if (worstDips < MIN_DIPS || (ratio != null && ratio < RATE_RATIO)) return@mapNotNull null
            val likely = worstDips >= LIKELY_DIPS && otherDips == 0
            Finding.OneTuner(guide, worst, worstDips, others.sorted(), otherDips, if (likely) Strength.LIKELY else Strength.POSSIBLE, score(ratio))
        }
        return candidates.maxByOrNull { it.score }
    }
}

/** Impulse noise: 3+ dips of symbol errors alone, snq never under the good line. Likely when they're half the dips or more. */
object ImpulseNoiseRule : DiagnosisRule {
    override fun evaluate(session: Diagnosable): Finding? {
        val good = session.thresholds.snqGood
        val noise = session.dips.filter { it.kind == EventKind.DIP && (it.minSnq ?: -1) >= good }
        if (noise.size < MIN_DIPS) return null
        val likely = noise.size * 2 >= session.dips.size
        return Finding.ImpulseNoise(
            noise.size, session.dips.size, noise.mapNotNull { it.minSeq }.minOrNull(),
            if (likely) Strength.LIKELY else Strength.POSSIBLE, noise.size.toDouble() / MIN_DIPS,
        )
    }
}

/**
 * Flutter: locked snq swinging 20+ points within 10 s, 5+ times (each swing counted once, then 10 s skipped).
 * Likely at 20+.
 */
object FlutterRule : DiagnosisRule {
    const val SWING = 20
    const val WINDOW_MS = 10_000L
    const val MIN_SWINGS = 5
    const val LIKELY_SWINGS = 20

    override fun evaluate(session: Diagnosable): Finding? {
        val perChannel = session.data.byChannel.mapValues { (_, rs) -> swings(rs.filter { it.sample.locked }) }.filterValues { it > 0 }
        val total = perChannel.values.sum()
        if (total < MIN_SWINGS) return null
        val channels = perChannel.entries.sortedByDescending { it.value }.map { it.key }
        return Finding.Flutter(total, channels, if (total >= LIKELY_SWINGS) Strength.LIKELY else Strength.POSSIBLE, total.toDouble() / MIN_SWINGS)
    }

    internal fun swings(rs: List<LoggedReading>): Int {
        var count = 0
        var start = 0
        var skipUntil = Long.MIN_VALUE
        for (i in rs.indices) {
            val now = rs[i].atMs
            while (rs[start].atMs < now - WINDOW_MS) start++
            if (now <= skipUntil) continue
            var lo = Int.MAX_VALUE
            var hi = Int.MIN_VALUE
            for (j in start..i) {
                lo = minOf(lo, rs[j].sample.snq)
                hi = maxOf(hi, rs[j].sample.snq)
            }
            if (hi - lo >= SWING) {
                count++
                skipUntil = now + WINDOW_MS
            }
        }
        return count
    }
}

/** Not RF: the device stopped answering. Likely when it did so twice or more, or there were no dips at all. */
object NotRfRule : DiagnosisRule {
    override fun evaluate(session: Diagnosable): Finding? {
        val outages = session.data.events.filter { it.kind == EventKind.DEVICE_UNREACHABLE }
        if (outages.isEmpty()) return null
        val likely = outages.size >= 2 || session.dips.isEmpty()
        return Finding.NotRf(
            outages.size, outages.sumOf { it.durationMs }, session.dips.size,
            if (likely) Strength.LIKELY else Strength.POSSIBLE, outages.size.toDouble(),
        )
    }
}
