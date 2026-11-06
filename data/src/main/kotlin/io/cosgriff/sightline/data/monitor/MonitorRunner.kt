package io.cosgriff.sightline.data.monitor

import io.cosgriff.sightline.analysis.DipAlert
import io.cosgriff.sightline.analysis.DipAlerts
import io.cosgriff.sightline.analysis.DipDetector
import io.cosgriff.sightline.analysis.ErrorCounters
import io.cosgriff.sightline.analysis.PacketErrorCounter
import io.cosgriff.sightline.analysis.PacketErrors
import io.cosgriff.sightline.analysis.Health
import io.cosgriff.sightline.analysis.SignalEvent
import io.cosgriff.sightline.analysis.SignalSample
import io.cosgriff.sightline.analysis.Thresholds
import io.cosgriff.sightline.analysis.TimelineSeries
import io.cosgriff.sightline.analysis.assess
import io.cosgriff.sightline.data.db.ReadingEntity
import io.cosgriff.sightline.data.device.Channel
import io.cosgriff.sightline.data.device.Device
import io.cosgriff.sightline.data.device.TunerLeases
import io.cosgriff.sightline.data.live.TunedChannelSink
import io.cosgriff.sightline.protocol.Backoff
import io.cosgriff.sightline.protocol.ClaimResult
import io.cosgriff.sightline.protocol.ClaimedTuner
import io.cosgriff.sightline.protocol.Hold
import io.cosgriff.sightline.protocol.Lock
import io.cosgriff.sightline.protocol.LostReason
import io.cosgriff.sightline.protocol.Modulation
import io.cosgriff.sightline.protocol.Observation
import io.cosgriff.sightline.protocol.Occupancy
import io.cosgriff.sightline.protocol.Poll
import io.cosgriff.sightline.protocol.RfChannels
import io.cosgriff.sightline.protocol.TunerDebug
import io.cosgriff.sightline.protocol.TunerSessions
import io.cosgriff.sightline.protocol.TunerStatus
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * What to monitor (SPEC Screens › Monitor setup). One channel is held for the whole session; several are rotated
 * through, [dwellMs] each. [tuner] is the one to use, or null for any idle one. [durationMs] null runs until
 * stopped.
 *
 * [watch]: read [tuner] while another client (Plex, a DVR) uses it, without holding it (GLOSSARY "Watch a tuner in
 * use"). The channel is whatever that client tunes; [channels] are then the lineup, for names, and may be empty.
 */
data class MonitorPlan(
    val device: Device,
    val channels: List<Channel>,
    val tuner: Int?,
    val dwellMs: Long,
    val durationMs: Long?,
    val pollIntervalMs: Long,
    val thresholds: Thresholds,
    val fetchWeather: Boolean,
    val notifyDips: Boolean,
    val watch: Boolean = false,
) {
    init {
        require(watch || channels.isNotEmpty()) { "a monitor needs a channel" }
        require(!watch || tuner != null) { "watching needs a tuner" }
    }

    val rotate: Boolean get() = !watch && channels.size > 1
}

/** What the monitor is doing right now. */
sealed interface MonitorPhase {
    /** Looking for an idle tuner and tuning it. */
    data object Claiming : MonitorPhase

    /** Holding [tuner] and reading it. [missedPolls] in a row got no answer. */
    data class Watching(val tuner: Int, val stream: Boolean, val missedPolls: Int = 0) : MonitorPhase

    /** Every tuner it may use is held by another client; tried again when one frees or every [retryEvery]. */
    data class AllBusy(val occupancy: Map<Int, Occupancy>, val retryEvery: Duration) : MonitorPhase

    /** The tuner was taken and no other could be had: waiting for one (SPEC "Tuner lost"). */
    data class Paused(val reason: LostReason, val retryEvery: Duration) : MonitorPhase

    /** The device didn't answer; tried again after [retryIn]. */
    data class Unreachable(val retryIn: Duration) : MonitorPhase

    /**
     * Watching [tuner] while another client uses it. [holder] is that client's address, [target] where the tuner
     * streams to; null when the tuner doesn't say. [missedPolls] in a row got no answer.
     */
    data class Observing(val tuner: Int, val holder: String?, val target: String?, val missedPolls: Int = 0) : MonitorPhase

    /** Watching [tuner], but nobody is using it: nothing is recorded until someone tunes it. */
    data class ObservingIdle(val tuner: Int) : MonitorPhase
}

/** Why a monitor session ended. */
enum class MonitorEnd {
    /** The planned duration ran out. */
    TIME_UP,

    /** Stopped by the user (the run was cancelled). */
    STOPPED,

    /** The device refused every channel. */
    NO_CHANNEL,
}

/**
 * A monitor session as it runs. [events] are finished, [open] in progress; [readings] counts every stored reading
 * and [goodReadings] those in good health, for "time good". [latest] is the last reading of [channel].
 */
data class MonitorStatus(
    val plan: MonitorPlan,
    val sessionId: Long,
    val startedAt: Long,
    val channel: Channel,
    val phase: MonitorPhase,
    val timeline: TimelineSeries,
    val events: List<SignalEvent> = emptyList(),
    val open: List<SignalEvent> = emptyList(),
    val readings: Long = 0,
    val goodReadings: Long = 0,
    val latest: TunerStatus? = null,
    /** Packet errors counted so far (SPEC "Packet errors"); [countersRead] false until a tuner gave its counters. */
    val errors: PacketErrors = PacketErrors.NONE,
    val countersRead: Boolean = false,
    val ended: MonitorEnd? = null,
    /** When it ended, once [ended] is set. */
    val endedAt: Long? = null,
) {
    /** When the plan runs out; null until stopped. */
    val endsAt: Long? get() = plan.durationMs?.let { startedAt + it }

    /** Every event so far, finished or not, oldest first. */
    val allEvents: List<SignalEvent> get() = events + open
}

/** Where a monitor session goes: a `MONITOR` session row, then readings and events in batches. */
interface MonitorLog {
    suspend fun begin(plan: MonitorPlan, startedAt: Long): Long
    suspend fun append(readings: List<ReadingEntity>, events: List<SignalEvent>, sessionId: Long)
    suspend fun end(sessionId: Long, endedAt: Long, tuner: Int?)

    /** A watched session's channels so far, in the order first seen, and the client last seen using the tuner. */
    suspend fun watched(sessionId: Long, channels: List<String>, holder: String?) = Unit
}

/**
 * Monitor's polling loop (SPEC "Polling loop"): claim an idle tuner, wait for lock, poll every interval, keep
 * readings in memory and write them every [flushEvery] in one transaction, move to the next channel after the
 * dwell in rotate mode, and release the tuner on every exit path. Never takes a busy tuner (CLAUDE.md): when all
 * are busy it waits, as Live does. A lost tuner is an event; another idle tuner is tried once, then it waits for
 * one. Failed polls go to the dip detector, which makes them a device-unreachable event; the control connection
 * reconnects with backoff by itself.
 */
class MonitorRunner(
    private val sessions: TunerSessions,
    private val leases: TunerLeases,
    private val sink: TunedChannelSink,
    private val log: MonitorLog,
    private val clock: Clock,
    private val onAlert: (DipAlert) -> Unit = {},
    private val lockWait: Duration = 3.seconds,
    private val lockPoll: Duration = 250.milliseconds,
    private val flushEvery: Duration = 10.seconds,
    private val busyRetry: Duration = 30.seconds,
) {
    private val _status = MutableStateFlow<MonitorStatus?>(null)
    val status: StateFlow<MonitorStatus?> = _status.asStateFlow()

    private lateinit var plan: MonitorPlan
    private lateinit var detector: DipDetector
    private val alerts = DipAlerts()
    private val buffer = mutableListOf<ReadingEntity>()
    private val finished = mutableListOf<SignalEvent>()
    private var sinceFlush = TimeSource.Monotonic.markNow()
    private var sessionId = 0L
    private val tunersUsed = mutableSetOf<Int>()
    private val errorCounter = PacketErrorCounter()
    private var lastTuner: Int? = null

    /** Runs the session until its time is up or the caller cancels it. Always ends the session and releases the tuner. */
    suspend fun run(plan: MonitorPlan): MonitorEnd {
        this.plan = plan
        detector = DipDetector(plan.thresholds)
        val startedAt = clock.millis()
        sessionId = log.begin(plan, startedAt)
        _status.value = MonitorStatus(
            plan, sessionId, startedAt, plan.channels.firstOrNull() ?: unknownChannel(""),
            if (plan.watch) MonitorPhase.ObservingIdle(plan.tuner!!) else MonitorPhase.Claiming,
            TimelineSeries.forPlan(startedAt, plan.durationMs, plan.pollIntervalMs),
        )
        var end = MonitorEnd.STOPPED
        try {
            end = if (plan.watch) observe(startedAt) else loop(startedAt)
            return end
        } finally {
            withContext(NonCancellable) {
                val at = clock.millis()
                add(detector.finish(at), at)
                flush()
                log.end(sessionId, at, tunersUsed.singleOrNull())
                _status.update { it?.copy(open = emptyList(), ended = end, endedAt = at) }
            }
        }
    }

    private suspend fun loop(startedAt: Long): MonitorEnd {
        val deadline = plan.durationMs?.let { startedAt + it }
        val refused = mutableSetOf<String>()
        var index = 0
        while (true) {
            if (deadline != null && clock.millis() >= deadline) return MonitorEnd.TIME_UP
            if (refused.size == plan.channels.size) return MonitorEnd.NO_CHANNEL
            val channel = plan.channels[index]
            index = (index + 1) % plan.channels.size
            if (channel.guideNumber in refused) continue
            val until = if (plan.rotate) minOf(clock.millis() + plan.dwellMs, deadline ?: Long.MAX_VALUE) else deadline
            if (!hold(channel, until)) refused += channel.guideNumber
        }
    }

    private fun publish(transform: (MonitorStatus) -> MonitorStatus) = _status.update { s -> s?.let(transform) }

    private fun timeLeft(until: Long?): Duration =
        until?.let { (it - clock.millis()).coerceAtLeast(0).milliseconds } ?: Duration.INFINITE

    /** Holds [channel] until [until] (null: until cancelled). False when the device refuses the channel. */
    private suspend fun hold(channel: Channel, until: Long?): Boolean {
        publish { it.copy(channel = channel, phase = MonitorPhase.Claiming, latest = null) }
        var unreachable = 0
        var waitedForOwn = false
        var lost: LostReason? = null
        var triedAnother = false
        var exclude: Int? = null
        while (timeLeft(until) > Duration.ZERO) {
            val order = plan.tuner?.let { listOf(it) }
                ?: (0 until plan.device.tunerCount).filter { it != exclude }.sortedBy { if (it == lastTuner) -1 else it }
            val result: Watch? = coroutineScope {
                when (val claim = sessions.claim(this, channel.target, order)) {
                    is ClaimResult.Claimed -> {
                        unreachable = 0
                        lost = null
                        watch(channel, claim.tuner, until)
                    }
                    is ClaimResult.NoneFree -> {
                        val held = leases.held.value
                        val at = clock.millis()
                        add(detector.pause(at), at)
                        val reason = lost
                        if (reason == null && leases.holdsAny(plan.device.deviceId) && !waitedForOwn) {
                            // Probably Live or Sweep letting go of a tuner as Monitor starts.
                            waitedForOwn = true
                            leases.awaitChange(held, OWN_RELEASE_WAIT)
                        } else {
                            publish {
                                it.copy(phase = reason?.let { r -> MonitorPhase.Paused(r, busyRetry) } ?: MonitorPhase.AllBusy(claim.occupancy, busyRetry))
                            }
                            flush()
                            leases.awaitChange(held, minOf(busyRetry, timeLeft(until)))
                        }
                        exclude = null
                        null
                    }
                    ClaimResult.UnknownChannel -> Watch.Refused
                    ClaimResult.Unreachable -> {
                        val at = clock.millis()
                        add(detector.missedPoll(at), at)
                        val wait = minOf(Backoff.delayAfter(++unreachable), timeLeft(until))
                        publish { it.copy(phase = MonitorPhase.Unreachable(wait)) }
                        flush()
                        delay(wait)
                        null
                    }
                }
            }
            when (result) {
                null -> Unit
                Watch.Done -> return true
                Watch.Refused -> return false
                is Watch.Lost -> {
                    val at = clock.millis()
                    add(detector.tunerLost(at, channel.guideNumber), at)
                    lost = result.reason
                    // Another idle tuner, once (SPEC "Tuner lost"); a tuner the user picked is waited for instead.
                    if (plan.tuner == null && !triedAnother) {
                        triedAnother = true
                        exclude = result.tuner
                    } else {
                        publish { it.copy(phase = MonitorPhase.Paused(result.reason, busyRetry)) }
                        flush()
                        delay(minOf(busyRetry, timeLeft(until)))
                    }
                }
            }
        }
        return true
    }

    private sealed interface Watch {
        /** Held until the end of the dwell or the session. */
        data object Done : Watch

        data object Refused : Watch

        data class Lost(val tuner: Int, val reason: LostReason) : Watch
    }

    private suspend fun watch(channel: Channel, tuner: ClaimedTuner, until: Long?): Watch {
        lastTuner = tuner.tuner
        tunersUsed += tuner.tuner
        errorCounter.retuned(tuner.tuner)
        leases.add(plan.device.deviceId, tuner.tuner)
        val stream = tuner.hold is Hold.Stream
        publish { it.copy(phase = MonitorPhase.Watching(tuner.tuner, stream)) }
        try {
            when (val waited = awaitLock(tuner)) {
                is LockWait.Lost -> return Watch.Lost(tuner.tuner, waited.reason)
                is LockWait.Locked -> {
                    val s = waited.status
                    val rf = RfChannels.of(s.channel)
                    sink.tuned(channel, rf?.number, rf?.centerHz, rf?.band?.name, (s.lock as? Lock.Locked)?.modulation == Modulation.ATSC3)
                    record(channel, tuner.tuner, s, tuner.debug())
                }
                // No lock after the wait: recorded, which the detector makes a dropout.
                is LockWait.NoLock -> waited.status?.let { record(channel, tuner.tuner, it, tuner.debug()) }
            }
            var missed = 0
            val interval = plan.pollIntervalMs.milliseconds
            var next = TimeSource.Monotonic.markNow() + interval
            while (true) {
                val wait = minOf(-next.elapsedNow(), timeLeft(until))
                if (wait > Duration.ZERO) delay(wait)
                if (timeLeft(until) <= Duration.ZERO) return Watch.Done
                next += interval
                // Behind (the phone slept): start the ticks again from now rather than polling to catch up.
                if (next.hasPassedNow()) next = TimeSource.Monotonic.markNow() + interval
                when (val poll = tuner.poll()) {
                    is Poll.Reading -> {
                        missed = 0
                        // One more get per poll: the counters catch errors between polls that seq can't.
                        record(channel, tuner.tuner, poll.status, tuner.debug())
                    }
                    is Poll.Unparseable -> {
                        missed = 0
                        buffer += ReadingEntity(
                            sessionId = sessionId, ts = clock.millis(), guideNumber = channel.guideNumber, tuner = tuner.tuner,
                            ss = 0, snq = 0, seq = 0, bps = null, pps = null, lock = LOCK_UNREAD, rawStatus = poll.raw,
                        )
                    }
                    is Poll.Unreachable -> {
                        val at = clock.millis()
                        add(detector.missedPoll(at), at)
                        missed++
                    }
                    is Poll.Lost -> return Watch.Lost(tuner.tuner, poll.reason)
                }
                val count = missed
                publish { it.copy(phase = MonitorPhase.Watching(tuner.tuner, stream, count), open = detector.open) }
                if (sinceFlush.elapsedNow() >= flushEvery) flush()
            }
        } finally {
            withContext(NonCancellable) {
                tuner.release()
                leases.remove(plan.device.deviceId, tuner.tuner)
                val at = clock.millis()
                add(detector.pause(at), at)
            }
        }
    }

    private sealed interface LockWait {
        data class Locked(val status: TunerStatus) : LockWait

        /** No lock within the wait; [status] is the last reading, if any came. */
        data class NoLock(val status: TunerStatus?) : LockWait

        data class Lost(val reason: LostReason) : LockWait
    }

    /**
     * SPEC "Polling loop" step 3: readings are thrown away until the tuner locks, or [lockWait] passes. The first
     * reading after a tune can show a lock with seq still 0 (SPEC "Status parsing"), so that one waits too.
     */
    private suspend fun awaitLock(tuner: ClaimedTuner): LockWait {
        val start = TimeSource.Monotonic.markNow()
        var last: TunerStatus? = null
        while (true) {
            when (val poll = tuner.poll()) {
                is Poll.Reading -> {
                    last = poll.status
                    if (poll.status.isLocked && poll.status.seq > 0) return LockWait.Locked(poll.status)
                }
                is Poll.Lost -> return LockWait.Lost(poll.reason)
                is Poll.Unparseable, is Poll.Unreachable -> Unit
            }
            if (start.elapsedNow() >= lockWait) return LockWait.NoLock(last)
            delay(lockPoll)
        }
    }

    /**
     * Watch mode: reads the plan's tuner every interval without ever claiming it, following whatever channel the
     * client using it tunes. An idle tuner pauses the dip detector (nobody watching, so no dropout); a new channel,
     * or a new tune after idle, starts its error counts from a fresh baseline. As when Monitor tunes itself, the
     * readings of the first [lockWait] after a tune are skipped until the tuner locks with seq above 0.
     */
    private suspend fun observe(startedAt: Long): MonitorEnd {
        val tuner = plan.tuner!!
        val deadline = plan.durationMs?.let { startedAt + it }
        val observer = sessions.observer(tuner)
        val byGuide = plan.channels.associateBy { it.guideNumber }
        val seen = LinkedHashSet<String>()
        var holderSeen: String? = null
        var current: String? = null
        var settling: TimeSource.Monotonic.ValueTimeMark? = null
        var missed = 0
        tunersUsed += tuner
        val interval = plan.pollIntervalMs.milliseconds
        var next = TimeSource.Monotonic.markNow()
        while (true) {
            val wait = minOf(-next.elapsedNow(), timeLeft(deadline))
            if (wait > Duration.ZERO) delay(wait)
            if (timeLeft(deadline) <= Duration.ZERO) return MonitorEnd.TIME_UP
            next += interval
            if (next.hasPassedNow()) next = TimeSource.Monotonic.markNow() + interval
            when (val o = observer.read()) {
                is Observation.InUse -> {
                    missed = 0
                    val channel = channelOf(o, byGuide)
                    if (channel.guideNumber != current) {
                        val at = clock.millis()
                        if (current != null) add(detector.pause(at), at)
                        errorCounter.retuned(tuner)
                        current = channel.guideNumber
                        settling = TimeSource.Monotonic.markNow()
                        publish { it.copy(channel = channel, latest = null) }
                    }
                    if (seen.add(channel.guideNumber) || (o.holder != null && o.holder != holderSeen)) {
                        holderSeen = o.holder ?: holderSeen
                        log.watched(sessionId, seen.toList(), holderSeen)
                    }
                    publish { it.copy(phase = MonitorPhase.Observing(tuner, o.holder, o.target)) }
                    val settled = settling == null || (o.status.isLocked && o.status.seq > 0) || settling.elapsedNow() >= lockWait
                    if (settled) {
                        if (settling != null && o.status.isLocked && channel.guideNumber in byGuide) {
                            val rf = RfChannels.of(o.status.channel)
                            sink.tuned(channel, rf?.number, rf?.centerHz, rf?.band?.name, (o.status.lock as? Lock.Locked)?.modulation == Modulation.ATSC3)
                        }
                        settling = null
                        record(channel, tuner, o.status, o.debug)
                    }
                }
                Observation.Idle -> {
                    missed = 0
                    if (current != null) {
                        val at = clock.millis()
                        add(detector.pause(at), at)
                        current = null
                    }
                    publish { it.copy(phase = MonitorPhase.ObservingIdle(tuner), latest = null) }
                }
                is Observation.Unparseable -> {
                    missed = 0
                    current?.let { guide ->
                        buffer += ReadingEntity(
                            sessionId = sessionId, ts = clock.millis(), guideNumber = guide, tuner = tuner,
                            ss = 0, snq = 0, seq = 0, bps = null, pps = null, lock = LOCK_UNREAD, rawStatus = o.raw,
                        )
                    }
                }
                is Observation.Unreachable -> {
                    val at = clock.millis()
                    add(detector.missedPoll(at), at)
                    val count = ++missed
                    publish { s -> s.copy(phase = (s.phase as? MonitorPhase.Observing)?.copy(missedPolls = count) ?: s.phase) }
                }
            }
            if (sinceFlush.elapsedNow() >= flushEvery) flush()
        }
    }

    /**
     * The lineup channel the client tuned: by guide number, else by the frequency or RF channel the tuner says.
     * One the lineup doesn't have is named by the tuner's own `ch=` value.
     */
    private fun channelOf(o: Observation.InUse, byGuide: Map<String, Channel>): Channel {
        o.guideNumber?.let { g -> return byGuide[g] ?: unknownChannel(g) }
        val rf = RfChannels.of(o.status.channel)?.number
        val match = rf?.let { n -> plan.channels.firstOrNull { it.rfChannel == n } }
        return match ?: unknownChannel(o.status.raw.substringAfter("ch=", "").substringBefore(' ').ifEmpty { o.status.raw })
    }

    private fun unknownChannel(guideNumber: String) =
        Channel(plan.device.deviceId, guideNumber, "", "", null, null, null, false)

    private fun record(channel: Channel, tuner: Int, s: TunerStatus, debug: TunerDebug?) {
        val at = clock.millis()
        val sample = SignalSample(s.isLocked, s.ss, s.snq, s.seq)
        buffer += ReadingEntity(
            sessionId = sessionId, ts = at, guideNumber = channel.guideNumber, tuner = tuner,
            ss = s.ss, snq = s.snq, seq = s.seq, bps = s.bps, pps = s.pps,
            lock = when (val l = s.lock) {
                Lock.None -> LOCK_NONE
                is Lock.Locked -> l.raw
            },
            transportErrors = debug?.transportErrors, crcErrors = debug?.crcErrors,
            resyncs = debug?.resync, networkErrors = debug?.networkErrors,
        )
        val added = debug?.let {
            errorCounter.add(tuner, channel.guideNumber, ErrorCounters(it.transportErrors, it.crcErrors, it.resync, it.networkErrors))
        }
        add(detector.reading(at, channel.guideNumber, sample), at)
        val good = plan.thresholds.assess(sample).health == Health.GOOD
        publish {
            it.copy(
                readings = it.readings + 1,
                goodReadings = it.goodReadings + if (good) 1 else 0,
                timeline = it.timeline + (at to sample),
                latest = s,
                errors = added?.let { a -> it.errors + a } ?: it.errors,
                countersRead = it.countersRead || added != null,
                open = detector.open,
            )
        }
    }

    /** Events the detector finished: kept for the next write, shown, and maybe notified. */
    private fun add(events: List<SignalEvent>, at: Long) {
        if (events.isEmpty()) return
        finished += events
        publish { it.copy(events = it.events + events, open = detector.open) }
        if (plan.notifyDips) events.forEach { e -> alerts.onEvent(e, at)?.let(onAlert) }
    }

    private suspend fun flush() {
        sinceFlush = TimeSource.Monotonic.markNow()
        if (buffer.isEmpty() && finished.isEmpty()) return
        val readings = buffer.toList()
        val events = finished.toList()
        buffer.clear()
        finished.clear()
        log.append(readings, events, sessionId)
    }

    private companion object {
        val OWN_RELEASE_WAIT = 3.seconds

        /** The `lock` value stored for a reading in good order but with no lock. */
        const val LOCK_NONE = "none"

        /** The `lock` value of a reading whose line couldn't be parsed; its [ReadingEntity.rawStatus] has the line. */
        const val LOCK_UNREAD = ""
    }
}
