package io.cosgriff.sightline.analysis

/**
 * A tuner as a session over several tuners keys it: its device's ID and its number there. The device ID is empty
 * for "the session's device" in sessions that read one device only.
 */
data class TunerKey(val deviceId: String, val tuner: Int)

/** One read of a tuner Sightline doesn't hold, as [DeviceWatch] takes it. */
sealed interface TunerRead {
    /** Someone has it tuned to [guideNumber]. [counters] are `/tunerN/debug`'s, null when they weren't read. */
    data class Tuned(val guideNumber: String, val sample: SignalSample, val counters: ErrorCounters? = null) : TunerRead

    /** Nobody is using it (`ch=none`). */
    data object Idle : TunerRead

    /** No usable answer this poll (no reply, or a line that couldn't be parsed): nothing is known of it. */
    data object Unread : TunerRead
}

/** What one answered poll of a device came to. */
data class WatchStep(
    /** The tuners whose reading this poll is to be stored, with the packet errors it added (null: no counters). */
    val recorded: Map<Int, PacketErrors?>,
    /** Tuners now on another channel than at their last reading, or tuned again after being idle. */
    val retuned: Set<Int>,
    /** Tuners whose reading was the first locked one after a tune: the channel's RF can be learned from it. */
    val firstLocked: Set<Int>,
    /** Events finished by this poll, each with its device and, for a tuner's own, its tuner. */
    val events: List<SignalEvent>,
)

/**
 * A watch of every tuner of one device (SPEC Screens › Monitor setup, "Watch every tuner"), fed each poll's reads
 * of all its tuners. Each tuner has its own [DipDetector] and packet error baseline, keyed by device and tuner,
 * and follows its tuner as one-tuner watch mode does: a channel change closes its dip and starts a fresh baseline,
 * readings in the [settleMs] after a tune are skipped until it locks with seq above 0, and an idle tuner pauses
 * and records nothing.
 *
 * Device unreachable and gap are the device's, not each tuner's, so a device that stops answering is one event
 * rather than one per tuner: [DipRules.unreachableAfter] polls in a row with no answer start it (its tuners' dips
 * close at their last readings), the next answered poll ends it; a silence over [DipRules.gapMs] is one gap.
 */
class DeviceWatch(
    val deviceId: String,
    private val thresholds: Thresholds,
    private val rules: DipRules = DipRules(),
    private val settleMs: Long = SETTLE_MS,
) {
    private inner class Tuner(val tuner: Int) {
        // Gaps are the device's: a tuner's own detector never makes one.
        val detector = DipDetector(thresholds, rules.copy(gapMs = Long.MAX_VALUE))
        var guideNumber: String? = null
        var settlingSince: Long? = null
        val key = TunerKey(deviceId, tuner)
    }

    private val tuners = sortedMapOf<Int, Tuner>()
    private val errors = PacketErrorCounter()
    private var lastPollMs: Long? = null
    private var missed = 0
    private var firstMissMs: Long? = null
    private var unreachableSince: Long? = null

    /** The channel each tuner is on now, by tuner; a tuner nobody is using is absent. */
    val channels: Map<Int, String>
        get() = tuners.values.mapNotNull { t -> t.guideNumber?.let { t.tuner to it } }.toMap()

    /** True while the device counts as unreachable. */
    val unreachable: Boolean get() = unreachableSince != null

    /** Events in progress, oldest first: each tuner's, and the device not answering. */
    val open: List<SignalEvent>
        get() = buildList {
            unreachableSince?.let { add(SignalEvent(EventKind.DEVICE_UNREACHABLE, null, it, lastPollMs ?: it, deviceId = deviceId)) }
            for (t in tuners.values) addAll(t.detector.open.map { it.on(t) })
        }.sortedBy { it.startMs }

    /** A poll at [atMs] the device answered, with what each tuner said; a tuner left out is [TunerRead.Unread]. */
    fun polled(atMs: Long, reads: Map<Int, TunerRead>): WatchStep {
        val out = mutableListOf<SignalEvent>()
        checkGap(atMs, out)
        unreachableSince?.let { out += SignalEvent(EventKind.DEVICE_UNREACHABLE, null, it, atMs, deviceId = deviceId) }
        unreachableSince = null
        missed = 0
        firstMissMs = null

        val recorded = linkedMapOf<Int, PacketErrors?>()
        val retuned = mutableSetOf<Int>()
        val firstLocked = mutableSetOf<Int>()
        for ((n, read) in reads.toSortedMap()) {
            when (read) {
                is TunerRead.Tuned -> {
                    val t = tuners.getOrPut(n) { Tuner(n) }
                    if (t.guideNumber != read.guideNumber) {
                        if (t.guideNumber != null) out += t.detector.pause(atMs).map { it.on(t) }
                        errors.retuned(t.key)
                        t.guideNumber = read.guideNumber
                        t.settlingSince = atMs
                        retuned += n
                    }
                    val s = read.sample
                    val since = t.settlingSince
                    val settled = since == null || (s.locked && s.seq > 0) || atMs - since >= settleMs
                    if (settled) {
                        if (since != null && s.locked) firstLocked += n
                        t.settlingSince = null
                        recorded[n] = read.counters?.let { errors.add(t.key, read.guideNumber, it) }
                        out += t.detector.reading(atMs, read.guideNumber, s).map { it.on(t) }
                    }
                }
                TunerRead.Idle -> tuners[n]?.let { t ->
                    if (t.guideNumber != null) out += t.detector.pause(atMs).map { it.on(t) }
                    t.guideNumber = null
                    t.settlingSince = null
                }
                TunerRead.Unread -> Unit
            }
        }
        lastPollMs = atMs
        return WatchStep(recorded, retuned, firstLocked, out)
    }

    /** A poll at [atMs] in which none of the device's tuners answered. */
    fun missed(atMs: Long): List<SignalEvent> {
        val out = mutableListOf<SignalEvent>()
        checkGap(atMs, out)
        if (missed++ == 0) firstMissMs = atMs
        if (missed >= rules.unreachableAfter && unreachableSince == null) {
            pauseAll(atMs, out)
            unreachableSince = firstMissMs ?: atMs
        }
        lastPollMs = atMs
        return out
    }

    /** The session ended at [atMs]: everything in progress, closed. */
    fun finish(atMs: Long): List<SignalEvent> {
        val out = mutableListOf<SignalEvent>()
        for (t in tuners.values) out += t.detector.finish(atMs).map { it.on(t) }
        unreachableSince?.let { out += SignalEvent(EventKind.DEVICE_UNREACHABLE, null, it, atMs, deviceId = deviceId) }
        unreachableSince = null
        return out
    }

    private fun checkGap(atMs: Long, out: MutableList<SignalEvent>) {
        val last = lastPollMs ?: return
        if (atMs - last <= rules.gapMs) return
        pauseAll(last, out)
        out += SignalEvent(EventKind.GAP, null, last, atMs, deviceId = deviceId)
    }

    /** Closes every tuner's dip at its last reading; nothing after it is known. */
    private fun pauseAll(atMs: Long, out: MutableList<SignalEvent>) {
        for (t in tuners.values) out += t.detector.pause(atMs).map { it.on(t) }
    }

    private fun SignalEvent.on(t: Tuner) = copy(deviceId = this@DeviceWatch.deviceId, tuner = t.tuner)

    companion object {
        /** As the polling loop's lock wait: readings this long after a tune are skipped until it locks with seq above 0. */
        const val SETTLE_MS = 3_000L
    }
}

/**
 * Whether this event belongs with [key]'s readings: one recorded on that tuner, or a device-wide one (unreachable,
 * gap) of its device. An event that names no device is the session's, so it belongs with every tuner.
 */
fun SignalEvent.concerns(key: TunerKey): Boolean =
    if (tuner != null) tuner == key.tuner && deviceId.orEmpty() == key.deviceId else deviceId == null || deviceId == key.deviceId
