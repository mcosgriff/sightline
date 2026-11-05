package io.cosgriff.sightline.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** What to tune. */
sealed interface TuneTarget {
    /**
     * A lineup channel by guide number. [streamUrl] is the lineup entry's `URL` (`http://<ip>:5004/auto/v9.1`);
     * without it an ATSC 3.0 channel can't be tuned (ADR-0004).
     */
    data class Virtual(val guideNumber: String, val streamUrl: String?) : TuneTarget

    /** An RF channel, `auto:<rf>`, for channels missing from the lineup. ATSC 1.0 and QAM only. */
    data class Rf(val channel: Int) : TuneTarget
}

/** Who has a tuner, from `/tunerN/status`, `/tunerN/lockkey` and `/tunerN/target`. */
sealed interface Occupancy {
    data object Idle : Occupancy

    /**
     * In use. [holder] is the lock holder's IP, or the stream target's host when a client tuned without a lock key;
     * null when neither says. Sightline never takes a busy tuner.
     */
    data class Busy(val holder: String?, val channel: TunedChannel) : Occupancy

    data object Unknown : Occupancy
}

/**
 * Where Sightline notes a lock key before claiming with it and forgets it after release, so a key left by a killed
 * process can be released on the next start (SPEC "Polling loop", stale lock-key recovery). `:data` keeps it in
 * DataStore.
 */
interface LockKeyStore {
    suspend fun remember(deviceId: String, tuner: Int, lockKey: Long)
    suspend fun forget(deviceId: String, tuner: Int)

    object None : LockKeyStore {
        override suspend fun remember(deviceId: String, tuner: Int, lockKey: Long) = Unit
        override suspend fun forget(deviceId: String, tuner: Int) = Unit
    }
}

sealed interface ClaimResult {
    data class Claimed(val tuner: ClaimedTuner) : ClaimResult

    /** No tuner was idle (or the idle ones can't tune this channel). Who holds each, by tuner number. */
    data class NoneFree(val occupancy: Map<Int, Occupancy>) : ClaimResult

    /** The device refused the channel itself: not in the lineup, or not a channel. */
    data object UnknownChannel : ClaimResult

    data object Unreachable : ClaimResult
}

/** One poll of a claimed tuner. */
sealed interface Poll {
    data class Reading(val status: TunerStatus) : Poll

    /** The tuner answered with a line Sightline can't read; shown raw. */
    data class Unparseable(val raw: String) : Poll

    /** The tuner is no longer Sightline's (SPEC "Tuner lost"). */
    data class Lost(val reason: LostReason) : Poll

    data class Unreachable(val reason: UnreachableReason) : Poll
}

sealed interface LostReason {
    /** Another client holds it now. [holder] as in [Occupancy.Busy]. */
    data class TakenBy(val holder: String?) : LostReason

    /** Still locked to Sightline but tuned elsewhere, or idle: someone changed it. */
    data class ChannelChanged(val now: TunedChannel) : LostReason

    /** The HTTP stream holding an ATSC 3.0 tuner ended. */
    data object StreamEnded : LostReason

    /** [ClaimedTuner.release] was called. */
    data object Released : LostReason
}

/**
 * Claims idle tuners on one device and gives them back (SPEC "Control commands used", GLOSSARY "Claim / release").
 *
 * ATSC 1.0 and QAM: set a random lock key, then tune with it. ATSC 3.0, which this firmware won't tune through the
 * control protocol (`invalid virtual channel`): an HTTP stream on that tuner, read and discarded (ADR-0004). Not
 * every tuner does ATSC 3.0 (two of four on a FLEX 4K, which say `806 Tune Failed`), so idle tuners are tried in
 * order. A tuner is only ever taken when status says `ch=none` and the lock key is `none`.
 */
class TunerSessions(
    private val deviceId: String,
    private val tunerCount: Int,
    private val control: ControlClient,
    private val http: HttpApi,
    private val lockKeys: LockKeyStore = LockKeyStore.None,
    private val random: Random = Random.Default,
) {
    /** Guide numbers that answered `invalid virtual channel` but streamed: ATSC 3.0, go straight to HTTP. */
    private val streamOnly = mutableSetOf<String>()

    suspend fun occupancy(tuner: Int): Occupancy {
        val status = when (val r = control.get("/tuner$tuner/status")) {
            is ControlResult.Value -> (StatusParser.parse(r.value) as? StatusParse.Parsed)?.status
                ?: return Occupancy.Unknown
            else -> return Occupancy.Unknown
        }
        val lockKey = (control.get("/tuner$tuner/lockkey") as? ControlResult.Value)?.value ?: return Occupancy.Unknown
        if (status.isIdle && lockKey == "none") return Occupancy.Idle
        return Occupancy.Busy(holder(tuner, lockKey), status.channel)
    }

    private suspend fun holder(tuner: Int, lockKey: String): String? {
        if (lockKey != "none") return lockKey
        val target = (control.get("/tuner$tuner/target") as? ControlResult.Value)?.value ?: return null
        if (target == "none") return null
        return target.substringAfter("://").substringBefore('/').substringBefore(':').ifEmpty { null }
    }

    /**
     * Claims the first idle tuner in [tuners] and tunes it to [target]. [scope] runs the HTTP stream for ATSC 3.0;
     * cancelling it ends the stream. The caller must [ClaimedTuner.release] (or use [withTuner]).
     */
    suspend fun claim(scope: CoroutineScope, target: TuneTarget, tuners: List<Int> = (0 until tunerCount).toList()): ClaimResult {
        val seen = LinkedHashMap<Int, Occupancy>()
        for (tuner in tuners) {
            val occupancy = occupancy(tuner)
            seen[tuner] = occupancy
            if (occupancy == Occupancy.Unknown && control.get("/sys/version") is ControlResult.Unreachable) {
                return ClaimResult.Unreachable
            }
            if (occupancy != Occupancy.Idle) continue
            when (val attempt = tryTuner(scope, tuner, target)) {
                is Attempt.Done -> return attempt.result
                Attempt.NextTuner -> seen[tuner] = occupancy(tuner)
            }
        }
        return ClaimResult.NoneFree(seen)
    }

    private sealed interface Attempt {
        data class Done(val result: ClaimResult) : Attempt
        data object NextTuner : Attempt
    }

    private suspend fun tryTuner(scope: CoroutineScope, tuner: Int, target: TuneTarget): Attempt {
        if (target is TuneTarget.Virtual && target.guideNumber in streamOnly) return tryStream(scope, tuner, target)

        val key = random.nextLong(1, 0xFFFFFFFFL)
        lockKeys.remember(deviceId, tuner, key)
        when (val r = control.set("/tuner$tuner/lockkey", key.toString())) {
            is ControlResult.Value -> Unit
            is ControlResult.Rejected -> {
                lockKeys.forget(deviceId, tuner)
                return Attempt.NextTuner // taken between the idle check and the claim
            }
            is ControlResult.Unreachable -> return Attempt.Done(ClaimResult.Unreachable) // key kept: stale next start
        }
        val tune = when (target) {
            is TuneTarget.Virtual -> control.set("/tuner$tuner/vchannel", target.guideNumber, key)
            is TuneTarget.Rf -> control.set("/tuner$tuner/channel", "auto:${target.channel}", key)
        }
        val tuned = (tune as? ControlResult.Value)?.let { channelNow(tuner) }
        if (tuned != null) {
            return Attempt.Done(ClaimResult.Claimed(ClaimedTuner(this, tuner, Hold.LockKey(key), target, tuned)))
        }
        releaseLockKey(tuner, key)
        val error = (tune as? ControlResult.Rejected)?.error
        return when {
            tune is ControlResult.Unreachable || (tune is ControlResult.Value) -> Attempt.Done(ClaimResult.Unreachable)
            error is ControlError.InvalidVirtualChannel && target is TuneTarget.Virtual && target.streamUrl != null ->
                tryStream(scope, tuner, target)
            error is ControlError.InvalidVirtualChannel || error is ControlError.InvalidChannel ->
                Attempt.Done(ClaimResult.UnknownChannel)
            else -> Attempt.NextTuner
        }
    }

    private suspend fun tryStream(scope: CoroutineScope, tuner: Int, target: TuneTarget.Virtual): Attempt {
        val url = target.streamUrl?.let { HttpApi.streamUrl(it, tuner) } ?: return Attempt.Done(ClaimResult.UnknownChannel)
        // Our own lock key was just released; check nobody took the tuner in that moment.
        if (occupancy(tuner) != Occupancy.Idle) return Attempt.NextTuner
        return when (val open = http.openStream(url)) {
            is StreamOpen.Opened -> {
                streamOnly += target.guideNumber
                val drain = scope.launch(start = CoroutineStart.UNDISPATCHED) { open.stream.drain() }
                val tuned = channelNow(tuner)
                if (tuned == null) {
                    open.stream.close()
                    drain.cancel()
                    Attempt.Done(ClaimResult.Unreachable)
                } else {
                    Attempt.Done(ClaimResult.Claimed(ClaimedTuner(this, tuner, Hold.Stream(open.stream, drain), target, tuned)))
                }
            }
            is StreamOpen.Refused -> when (open.refusal) {
                StreamRefusal.UnknownChannel -> Attempt.Done(ClaimResult.UnknownChannel)
                else -> Attempt.NextTuner
            }
            StreamOpen.Unreachable -> Attempt.Done(ClaimResult.Unreachable)
        }
    }

    /** `/tunerN/channel` right after a tune: the `ch=` value later polls must keep showing. */
    private suspend fun channelNow(tuner: Int): TunedChannel? =
        (control.get("/tuner$tuner/channel") as? ControlResult.Value)?.let { StatusParser.parseChannel(it.value) }

    private suspend fun releaseLockKey(tuner: Int, key: Long) {
        val channel = control.set("/tuner$tuner/channel", "none", key)
        val lock = control.set("/tuner$tuner/lockkey", "none", key)
        // Only forget the key once the tuner has it back; otherwise the next start releases it.
        if (channel !is ControlResult.Unreachable && lock !is ControlResult.Unreachable) lockKeys.forget(deviceId, tuner)
    }

    /**
     * Releases a tuner Sightline claimed in an earlier process (a stale lock key). Does nothing to a tuner whose
     * lock is held by anyone else: the device refuses a set with the wrong key. True when the key is dealt with.
     */
    suspend fun releaseStale(tuner: Int, lockKey: Long): Boolean {
        val channel = control.set("/tuner$tuner/channel", "none", lockKey)
        val lock = control.set("/tuner$tuner/lockkey", "none", lockKey)
        val done = channel !is ControlResult.Unreachable && lock !is ControlResult.Unreachable
        if (done) lockKeys.forget(deviceId, tuner)
        return done
    }

    internal suspend fun poll(claimed: ClaimedTuner): Poll {
        val hold = claimed.hold
        if (hold is Hold.Stream && !hold.drain.isActive) return Poll.Lost(LostReason.StreamEnded)
        val status = when (val r = control.get("/tuner${claimed.tuner}/status")) {
            is ControlResult.Value -> when (val p = StatusParser.parse(r.value)) {
                is StatusParse.Parsed -> p.status
                is StatusParse.Unparseable -> return Poll.Unparseable(p.raw)
            }
            is ControlResult.Rejected -> return Poll.Unparseable(r.error.message)
            is ControlResult.Unreachable -> return Poll.Unreachable(r.reason)
        }
        if (status.channel == claimed.channel) return Poll.Reading(status)
        // Not what Sightline tuned: a DVR on the same unit, or another app (SPEC Edge cases).
        val lockKey = (control.get("/tuner${claimed.tuner}/lockkey") as? ControlResult.Value)?.value
        val mine = lockKey != null && lockKey == control.localAddress?.hostAddress
        return if (lockKey != null && lockKey != "none" && !mine) Poll.Lost(LostReason.TakenBy(lockKey))
        else Poll.Lost(LostReason.ChannelChanged(status.channel))
    }

    internal suspend fun release(claimed: ClaimedTuner) = withContext(NonCancellable) {
        when (val hold = claimed.hold) {
            is Hold.LockKey -> releaseLockKey(claimed.tuner, hold.key)
            is Hold.Stream -> {
                hold.stream.close()
                hold.drain.cancel()
            }
        }
    }

    /** Claims, runs [block] and always releases, on success, failure or cancellation. */
    suspend fun <T> withTuner(
        scope: CoroutineScope,
        target: TuneTarget,
        block: suspend (ClaimedTuner) -> T,
    ): WithTuner<T> = when (val claim = claim(scope, target)) {
        is ClaimResult.Claimed -> try {
            WithTuner.Ran(block(claim.tuner))
        } finally {
            claim.tuner.release()
        }
        else -> WithTuner.NotClaimed(claim)
    }
}

sealed interface WithTuner<out T> {
    data class Ran<T>(val value: T) : WithTuner<T>
    data class NotClaimed(val why: ClaimResult) : WithTuner<Nothing>
}

/** How Sightline holds a tuner. */
sealed interface Hold {
    data class LockKey(val key: Long) : Hold
    class Stream(val stream: TunerStream, val drain: Job) : Hold
}

/** A tuner Sightline holds. Poll it at the caller's interval; release it on every exit path. */
class ClaimedTuner internal constructor(
    private val sessions: TunerSessions,
    val tuner: Int,
    val hold: Hold,
    val target: TuneTarget,
    /** The `ch=` value the tune produced. A poll showing anything else means the tuner was taken. */
    val channel: TunedChannel,
) {
    @Volatile
    private var released = false

    suspend fun poll(): Poll = if (released) Poll.Lost(LostReason.Released) else sessions.poll(this)

    /** Gives the tuner back. Safe to call more than once and from a cancelled coroutine. */
    suspend fun release() {
        if (released) return
        released = true
        sessions.release(this)
    }
}
