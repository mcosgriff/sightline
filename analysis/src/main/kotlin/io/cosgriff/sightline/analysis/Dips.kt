package io.cosgriff.sightline.analysis

/** What the dip detector records (GLOSSARY "Event"). Stored by name. */
enum class EventKind {
    /** snq under the poor threshold or seq under 100, for 2 readings in a row, until 5 good readings. */
    DIP,

    /** A dip in which the tuner lost lock at least once. */
    DROPOUT,

    /** Another client took the tuner or retuned it. Lasts until the monitor reads again. */
    TUNER_LOST,

    /** The device stopped answering polls: a network problem, never mistaken for an RF one. */
    DEVICE_UNREACHABLE,

    /** No poll for longer than [DipRules.gapMs]: the phone wasn't polling (Doze), so nothing is known. */
    GAP,
}

/**
 * One recorded event, times in UTC epoch ms. [guideNumber] is the channel it happened on, null for device-wide
 * events. [minSnq] and [minSeq] are the lowest values read while locked during it, null when it never locked;
 * [noLockMs] how much of it had no lock.
 */
data class SignalEvent(
    val kind: EventKind,
    val guideNumber: String?,
    val startMs: Long,
    val endMs: Long,
    val minSnq: Int? = null,
    val minSeq: Int? = null,
    val noLockMs: Long = 0,
) {
    val durationMs: Long get() = endMs - startMs

    /** A dip or a dropout: the signal itself was bad. */
    val isDip: Boolean get() = kind == EventKind.DIP || kind == EventKind.DROPOUT
}

/** The detector's rules (SPEC "Dip detection"). */
data class DipRules(
    /** Bad readings in a row that start a dip. A reading without lock starts one by itself. */
    val enterAfter: Int = 2,
    /** Good readings in a row that end one. */
    val exitAfter: Int = 5,
    /** A dip starting less than this after the last one ended joins it. */
    val mergeMs: Long = 10_000,
    /** Failed polls in a row that make the device unreachable. */
    val unreachableAfter: Int = 3,
    /** A silence longer than this between polls is a gap. */
    val gapMs: Long = 60_000,
)

/**
 * The dip detector (SPEC "Dip detection"): fed every poll of a monitor session in time order, it returns the
 * events that are finished. A reading is bad when it has no lock, snq is under the poor threshold
 * ([Thresholds.snqMarginal]) or seq is under [Thresholds.seqGood]. No lock starts a dropout at once; otherwise
 * [DipRules.enterAfter] bad readings in a row start a dip. [DipRules.exitAfter] good readings in a row end it, at
 * the first of them. A dip starting less than [DipRules.mergeMs] after the last one ended is merged into it, so a
 * finished dip is only returned once that window has passed.
 *
 * Failed polls make a device-unreachable event, a silence a gap, a lost tuner its own event; each closes the dip
 * in progress at the last reading, since nothing after it is known. [pause] does the same without an event, for
 * when the monitor stops polling on purpose (changing channel, waiting for a tuner). A reading on another channel
 * than the last (rotate mode) closes the last channel's dip.
 */
class DipDetector(private val thresholds: Thresholds, private val rules: DipRules = DipRules()) {
    /** A dip in progress. */
    private class Dip(val guide: String, val startMs: Long) {
        var minSnq: Int? = null
        var minSeq: Int? = null
        var noLockMs = 0L
        var dropout = false
        var lastMs: Long? = null
        var lastLocked = true
        var goodRun = 0
        var goodSinceMs: Long? = null

        /**
         * Folds in a reading. Every reading measures the time without lock; only [bad] ones count towards the lowest
         * values, so the recovery doesn't show as a dropout's "lowest quality".
         */
        fun add(atMs: Long, sample: SignalSample, bad: Boolean) {
            val last = lastMs
            if (last != null && !lastLocked) noLockMs += atMs - last
            if (!sample.locked) {
                dropout = true
            } else if (bad) {
                minSnq = minOf(minSnq ?: sample.snq, sample.snq)
                minSeq = minOf(minSeq ?: sample.seq, sample.seq)
            }
            lastMs = atMs
            lastLocked = sample.locked
        }

        fun event(endMs: Long = goodSinceMs ?: lastMs ?: startMs) =
            SignalEvent(if (dropout) EventKind.DROPOUT else EventKind.DIP, guide, startMs, endMs, minSnq, minSeq, noLockMs)
    }

    private var guide: String? = null
    private var lastObservedMs: Long? = null
    private var paused = false

    /** Bad readings not yet enough to be a dip. */
    private val candidate = mutableListOf<Pair<Long, SignalSample>>()
    private var dip: Dip? = null

    /** A finished dip held back while a following one could still merge into it. */
    private var pending: SignalEvent? = null

    private var missed = 0
    private var firstMissMs: Long? = null
    private var unreachableSince: Long? = null
    private var lostSince: Pair<Long, String?>? = null

    /** Events in progress, oldest first: shown live, not yet final. A dip waiting out its merge window is here too. */
    val open: List<SignalEvent>
        get() = buildList {
            pending?.let(::add)
            dip?.let { add(it.event()) }
            unreachableSince?.let { add(SignalEvent(EventKind.DEVICE_UNREACHABLE, null, it, lastObservedMs ?: it)) }
            lostSince?.let { (at, g) -> add(SignalEvent(EventKind.TUNER_LOST, g, at, lastObservedMs ?: at)) }
        }

    fun reading(atMs: Long, guideNumber: String, sample: SignalSample): List<SignalEvent> {
        val out = mutableListOf<SignalEvent>()
        checkGap(atMs, out)
        if (guide != null && guide != guideNumber) closeDip(out)
        guide = guideNumber
        endOutages(atMs, out)
        paused = false

        val bad = !sample.locked || sample.snq < thresholds.snqMarginal || sample.seq < thresholds.seqGood
        val current = dip
        when {
            current != null -> {
                current.add(atMs, sample, bad)
                if (bad) {
                    current.goodRun = 0
                    current.goodSinceMs = null
                } else {
                    if (current.goodRun++ == 0) current.goodSinceMs = atMs
                    if (current.goodRun >= rules.exitAfter) {
                        pending = current.event()
                        dip = null
                    }
                }
            }
            bad -> {
                candidate += atMs to sample
                if (!sample.locked || candidate.size >= rules.enterAfter) startDip(guideNumber, out)
            }
            else -> candidate.clear()
        }
        // A finished dip whose merge window passed with nothing new is final.
        pending?.let { p ->
            if (dip == null && candidate.isEmpty() && atMs - p.endMs >= rules.mergeMs) {
                out += p
                pending = null
            }
        }
        lastObservedMs = atMs
        return out
    }

    /** A poll that got no answer. */
    fun missedPoll(atMs: Long): List<SignalEvent> {
        val out = mutableListOf<SignalEvent>()
        checkGap(atMs, out)
        if (missed++ == 0) firstMissMs = atMs
        if (missed >= rules.unreachableAfter && unreachableSince == null) {
            closeDip(out)
            unreachableSince = firstMissMs ?: atMs
        }
        lastObservedMs = atMs
        return out
    }

    /** The tuner was taken from the monitor. The event lasts until the next reading, however long that takes. */
    fun tunerLost(atMs: Long, guideNumber: String?): List<SignalEvent> {
        val out = pause(atMs)
        if (lostSince == null) lostSince = atMs to guideNumber
        return out
    }

    /** The monitor stops polling on purpose; the silence that follows is not a gap. */
    fun pause(atMs: Long): List<SignalEvent> {
        val out = mutableListOf<SignalEvent>()
        checkGap(atMs, out)
        closeDip(out)
        paused = true
        lastObservedMs = atMs
        return out
    }

    /** The session ended: everything in progress, closed at the last reading, or at [atMs] for outages. */
    fun finish(atMs: Long): List<SignalEvent> {
        val out = mutableListOf<SignalEvent>()
        closeDip(out)
        endOutages(atMs, out)
        return out
    }

    private fun startDip(guideNumber: String, out: MutableList<SignalEvent>) {
        val startMs = candidate.first().first
        val p = pending
        val merge = p != null && p.guideNumber == guideNumber && startMs - p.endMs < rules.mergeMs
        val next = if (merge) {
            Dip(guideNumber, p.startMs).apply {
                minSnq = p.minSnq
                minSeq = p.minSeq
                noLockMs = p.noLockMs
                dropout = p.kind == EventKind.DROPOUT
            }
        } else {
            p?.let { out += it }
            Dip(guideNumber, startMs)
        }
        pending = null
        candidate.forEach { (at, s) -> next.add(at, s, bad = true) }
        candidate.clear()
        dip = next
    }

    private fun endOutages(atMs: Long, out: MutableList<SignalEvent>) {
        unreachableSince?.let { out += SignalEvent(EventKind.DEVICE_UNREACHABLE, null, it, atMs) }
        unreachableSince = null
        missed = 0
        firstMissMs = null
        lostSince?.let { (at, g) -> out += SignalEvent(EventKind.TUNER_LOST, g, at, atMs) }
        lostSince = null
    }

    private fun checkGap(atMs: Long, out: MutableList<SignalEvent>) {
        val last = lastObservedMs ?: return
        if (paused || atMs - last <= rules.gapMs) return
        closeDip(out)
        out += SignalEvent(EventKind.GAP, guide, last, atMs)
    }

    /** Ends the dip in progress where its readings end, and lets go of one waiting to merge. */
    private fun closeDip(out: MutableList<SignalEvent>) {
        pending?.let { out += it }
        pending = null
        dip?.let { out += it.event() }
        dip = null
        candidate.clear()
    }
}

/** Dips (and dropouts) among [this], counted as the running screen shows them. */
fun List<SignalEvent>.dipCount(): Int = count { it.isDip }

/** The longest dip or dropout, in ms; 0 when there was none. */
fun List<SignalEvent>.longestDipMs(): Long = filter { it.isDip }.maxOfOrNull { it.durationMs } ?: 0
