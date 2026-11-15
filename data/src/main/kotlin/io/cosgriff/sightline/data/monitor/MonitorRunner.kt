package io.cosgriff.sightline.data.monitor

import io.cosgriff.sightline.analysis.DipAlert
import io.cosgriff.sightline.analysis.DipAlerts
import io.cosgriff.sightline.analysis.DeviceWatch
import io.cosgriff.sightline.analysis.DipDetector
import io.cosgriff.sightline.analysis.ErrorCounters
import io.cosgriff.sightline.analysis.EventKind
import io.cosgriff.sightline.analysis.TunerKey
import io.cosgriff.sightline.analysis.PacketErrorCounter
import io.cosgriff.sightline.analysis.PacketErrors
import io.cosgriff.sightline.analysis.Health
import io.cosgriff.sightline.analysis.SignalEvent
import io.cosgriff.sightline.analysis.SignalSample
import io.cosgriff.sightline.analysis.Thresholds
import io.cosgriff.sightline.analysis.TimelineSeries
import io.cosgriff.sightline.analysis.TunerRead
import io.cosgriff.sightline.analysis.WifiSample
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
import io.cosgriff.sightline.protocol.TunerObserver
import io.cosgriff.sightline.protocol.TunerSessions
import io.cosgriff.sightline.protocol.TunerStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
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
 *
 * [wholeDevices]: watch every tuner of these devices the same way (GLOSSARY "Whole-device watch"); [device] is the
 * first of them, [tuner] null, and [channels] every device's lineup, each channel naming its device.
 *
 * [compareUnits]: a two-unit compare (GLOSSARY "Two-unit compare"): the same channel on an idle tuner of each of these
 * two devices at once, [device] the first, [tuner] null, and [channels] that channel on each device, one apiece with
 * the same guide number. A tuner is only claimed on a unit while another of its tuners stays idle, for the DVR.
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
    val wholeDevices: List<Device> = emptyList(),
    val compareUnits: List<Device> = emptyList(),
) {
    init {
        require(watch || wholeDevice || channels.isNotEmpty()) { "a monitor needs a channel" }
        require(!watch || tuner != null) { "watching needs a tuner" }
        require(!wholeDevice || (!watch && tuner == null && wholeDevices.first().deviceId == device.deviceId)) {
            "a whole-device watch picks no tuner, and its device is the first watched"
        }
        require(
            !compare || (
                compareUnits.size == 2 && !watch && !wholeDevice && tuner == null &&
                    compareUnits.first().deviceId == device.deviceId && compareUnits[0].deviceId != compareUnits[1].deviceId &&
                    compareUnits.all { d -> channels.count { it.deviceId == d.deviceId } == 1 } && channels.size == 2 &&
                    channels.map { it.guideNumber }.distinct().size == 1
                ),
        ) { "a two-unit compare is one channel on each of two devices, its device the first" }
    }

    /** A watch of every tuner of [wholeDevices]. */
    val wholeDevice: Boolean get() = wholeDevices.isNotEmpty()

    /** A two-unit compare of [compareUnits]. */
    val compare: Boolean get() = compareUnits.isNotEmpty()

    val rotate: Boolean get() = !watch && !wholeDevice && !compare && channels.size > 1
}

/** What one tuner of a whole-device watch is doing, as the running view shows it. */
sealed interface WatchedUse {
    /** Not read yet. */
    data object Unknown : WatchedUse

    /** Nobody is using it: nothing is recorded. */
    data object Idle : WatchedUse

    /**
     * [holder] (an address, null when the tuner doesn't say) has it on [channel]. [latest] is its last reading, null
     * while the readings after a tune are being skipped.
     */
    data class InUse(val holder: String?, val channel: Channel, val latest: TunerStatus?) : WatchedUse

    /** It gave no usable answer at the last poll. */
    data object NoAnswer : WatchedUse
}

/** One tuner of a whole-device watch: which device ([deviceId], [deviceName]), which tuner, and what it's doing. */
data class WatchedTuner(val deviceId: String, val deviceName: String, val tuner: Int, val use: WatchedUse = WatchedUse.Unknown)

/** What one unit of a two-unit compare is doing (SPEC Screens › Monitor running, "Compare two units"). */
sealed interface UnitState {
    /** Checking for a spare tuner and claiming one. */
    data object Claiming : UnitState

    /** Holding [tuner] and reading it; [stream] when held by an HTTP stream (ATSC 3.0). [missedPolls] in a row got no answer. */
    data class Holding(val tuner: Int, val stream: Boolean, val missedPolls: Int = 0) : UnitState

    /**
     * Fewer than two of its tuners are idle, so none can be taken while one stays free for the DVR; tried again every
     * [retryEvery]. At the start of a session this refuses it.
     */
    data class NoSpare(val retryEvery: Duration) : UnitState

    /** Its tuner was taken and no other could be had; waiting (SPEC "Tuner lost"). */
    data class Paused(val reason: LostReason, val retryEvery: Duration) : UnitState

    /** The unit didn't answer; tried again after [retryIn]. At the start of a session this refuses it. */
    data class Unreachable(val retryIn: Duration) : UnitState
}

/** One unit of a two-unit compare: which device, what it's doing, its line on the timeline and its last reading. */
data class ComparedUnit(
    val deviceId: String,
    val deviceName: String,
    val timeline: TimelineSeries,
    val state: UnitState = UnitState.Claiming,
    val latest: TunerStatus? = null,
)

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

    /**
     * Watching every tuner of the plan's devices ([MonitorStatus.tuners] says what each is doing). [unreachable] are
     * the devices that didn't answer at their last poll.
     */
    data class WatchingAll(val unreachable: Set<String> = emptySet()) : MonitorPhase

    /** Comparing two units ([MonitorStatus.units] says what each is doing). */
    data object Comparing : MonitorPhase
}

/** Why a monitor session ended. */
enum class MonitorEnd {
    /** The planned duration ran out. */
    TIME_UP,

    /** Stopped by the user (the run was cancelled). */
    STOPPED,

    /** The device refused every channel. */
    NO_CHANNEL,

    /**
     * A two-unit compare didn't start: a unit had no idle tuner to spare for it, or didn't answer
     * ([MonitorStatus.units] says which). Nothing was claimed and no session was saved.
     */
    NO_SPARE_TUNER,
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
    /** A whole-device watch's tuners, by device in the plan's order then by tuner; empty in the other modes. */
    val tuners: List<WatchedTuner> = emptyList(),
    /** A two-unit compare's units, in the plan's order; empty in the other modes. */
    val units: List<ComparedUnit> = emptyList(),
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

    /**
     * A batch with the phone's Wi-Fi samples taken meanwhile (SPEC "Phone Wi-Fi"), in the same transaction. A log
     * that keeps no Wi-Fi drops them.
     */
    suspend fun append(readings: List<ReadingEntity>, events: List<SignalEvent>, wifi: List<WifiSample>, sessionId: Long) =
        append(readings, events, sessionId)

    suspend fun end(sessionId: Long, endedAt: Long, tuner: Int?)

    /** A watched session's channels so far, in the order first seen, and the client last seen using the tuner. */
    suspend fun watched(sessionId: Long, channels: List<String>, holder: String?) = Unit

    /** A whole-device watch's channels so far, on any tuner, and every client seen, each in the order first seen. */
    suspend fun watchedAll(sessionId: Long, channels: List<String>, holders: List<String>) = Unit
}

/**
 * Monitor's polling loop (SPEC "Polling loop"): claim an idle tuner, wait for lock, poll every interval, keep
 * readings in memory and write them every [flushEvery] in one transaction, move to the next channel after the
 * dwell in rotate mode, and release the tuner on every exit path. Never takes a busy tuner (CLAUDE.md): when all
 * are busy it waits, as Live does. A lost tuner is an event; another idle tuner is tried once, then it waits for
 * one. Failed polls go to the dip detector, which makes them a device-unreachable event; the control connection
 * reconnects with backoff by itself.
 *
 * [sessionsOf] gives each device of a whole-device watch its own control connection; [sessions] is the plan's
 * device's. [wifi] is the phone's own Wi-Fi, recorded in every mode for as long as the session runs (SPEC "Phone
 * Wi-Fi"): each state that differs from the last is a sample, stamped with [clock], written with the next batch.
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
    private val sessionsOf: (Device) -> TunerSessions = { sessions },
    private val wifi: PhoneWifi = PhoneWifi.NONE,
) {
    private val _status = MutableStateFlow<MonitorStatus?>(null)
    val status: StateFlow<MonitorStatus?> = _status.asStateFlow()

    private lateinit var plan: MonitorPlan
    private lateinit var detector: DipDetector
    private val alerts = DipAlerts()
    private val buffer = mutableListOf<ReadingEntity>()
    private val finished = mutableListOf<SignalEvent>()

    /** Wi-Fi samples not yet written. Filled from the Wi-Fi's own coroutine, so every use holds its lock. */
    private val wifiBuffer = mutableListOf<WifiSample>()
    private var sinceFlush = TimeSource.Monotonic.markNow()
    private var sessionId = 0L
    private val tunersUsed = mutableSetOf<Int>()
    private val errorCounter = PacketErrorCounter()
    private var lastTuner: Int? = null
    private val deviceWatches = mutableListOf<DeviceWatch>()
    private val units = mutableListOf<UnitRun>()

    /** Runs the session until its time is up or the caller cancels it. Always ends the session and releases the tuner. */
    suspend fun run(plan: MonitorPlan): MonitorEnd {
        this.plan = plan
        detector = DipDetector(plan.thresholds)
        val startedAt = clock.millis()
        val initial = MonitorStatus(
            plan, 0, startedAt, (if (plan.wholeDevice) null else plan.channels.firstOrNull()) ?: unknownChannel(""),
            when {
                plan.watch -> MonitorPhase.ObservingIdle(plan.tuner!!)
                plan.wholeDevice -> MonitorPhase.WatchingAll()
                plan.compare -> MonitorPhase.Comparing
                else -> MonitorPhase.Claiming
            },
            TimelineSeries.forPlan(startedAt, plan.durationMs, plan.pollIntervalMs),
            tuners = plan.wholeDevices.flatMap { d -> (0 until d.tunerCount).map { WatchedTuner(d.deviceId, d.friendlyName, it) } },
            units = plan.compareUnits.map { d -> ComparedUnit(d.deviceId, d.friendlyName, TimelineSeries.forPlan(startedAt, plan.durationMs, plan.pollIntervalMs)) },
        )
        if (plan.compare) {
            units += plan.compareUnits.map { d -> UnitRun(d, sessionsFor(d), plan.channels.single { it.deviceId == d.deviceId }, DipDetector(plan.thresholds)) }
            _status.value = initial
            // Refused before anything is claimed or saved when a unit can't spare a tuner (SPEC Screens › Monitor setup).
            val refused = refusals()
            if (refused.isNotEmpty()) {
                val at = clock.millis()
                _status.update { s -> s?.copy(units = s.units.map { u -> refused[u.deviceId]?.let { u.copy(state = it) } ?: u }, ended = MonitorEnd.NO_SPARE_TUNER, endedAt = at) }
                return MonitorEnd.NO_SPARE_TUNER
            }
        }
        sessionId = log.begin(plan, startedAt)
        _status.value = (_status.value?.takeIf { plan.compare } ?: initial).copy(sessionId = sessionId)
        var end = MonitorEnd.STOPPED
        try {
            end = coroutineScope {
                val sampling = launch { recordWifi() }
                try {
                    when {
                        plan.watch -> observe(startedAt)
                        plan.wholeDevice -> observeAll(startedAt)
                        plan.compare -> compare(startedAt)
                        else -> loop(startedAt)
                    }
                } finally {
                    sampling.cancel()
                }
            }
            return end
        } finally {
            withContext(NonCancellable) {
                val at = clock.millis()
                add(detector.finish(at), at)
                deviceWatches.forEach { add(it.finish(at), at) }
                units.forEach { addFor(it, it.detector.finish(at), at) }
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

    /** One unit of a two-unit compare: its device, control connection, channel there and dip detector. */
    private class UnitRun(val device: Device, val sessions: TunerSessions, val channel: Channel, val detector: DipDetector) {
        /** The tuner it holds, or last held: its events name it. */
        var tuner: Int? = null
    }

    /** A unit refused the channel: the whole compare ends. */
    private class ChannelRefused : RuntimeException()

    private fun sessionsFor(d: Device) = if (d.deviceId == plan.device.deviceId) sessions else sessionsOf(d)

    /**
     * A two-unit compare (SPEC Screens › Monitor setup, "Compare two units"): each unit claims and holds a tuner on its
     * own, side by side, each keeping one tuner of its device idle for the DVR. Both run on one thread, so the shared
     * buffer and status need no locks; the control connections' I/O happens elsewhere. A unit that refuses the
     * channel ends both, and its failure cancels the other, whose release runs as on every exit.
     */
    private suspend fun compare(startedAt: Long): MonitorEnd {
        val deadline = plan.durationMs?.let { startedAt + it }
        return try {
            withContext(serial) { coroutineScope { for (u in units) launch { holdUnit(u, deadline) } } }
            MonitorEnd.TIME_UP
        } catch (_: ChannelRefused) {
            MonitorEnd.NO_CHANNEL
        }
    }

    /** One thread for a compare's two units; I/O moves off it, so neither waits for the other. */
    private val serial = Dispatchers.Default.limitedParallelism(1)

    /**
     * Who can't spare a tuner at the start, by device ID: fewer than two idle tuners, or no answer. Sightline's own
     * tuners on a unit (Live letting go as Monitor starts) are waited for once.
     */
    private suspend fun refusals(): Map<String, UnitState> {
        val out = linkedMapOf<String, UnitState>()
        for (u in units) {
            val held = leases.held.value
            var idle = idleTuners(u)
            if (idle != null && idle.size < 2 && leases.holdsAny(u.device.deviceId)) {
                leases.awaitChange(held, OWN_RELEASE_WAIT)
                idle = idleTuners(u)
            }
            when {
                idle == null -> out[u.device.deviceId] = UnitState.Unreachable(Duration.ZERO)
                idle.size < 2 -> out[u.device.deviceId] = UnitState.NoSpare(busyRetry)
            }
        }
        return out
    }

    /** The unit's idle tuners (`ch=none` and no lock key), gets only; null when it answered for none. */
    private suspend fun idleTuners(u: UnitRun): List<Int>? {
        val seen = (0 until u.device.tunerCount).map { u.sessions.occupancy(it) }
        if (seen.all { it == Occupancy.Unknown }) return null
        return seen.indices.filter { seen[it] == Occupancy.Idle }
    }

    /**
     * Whether a tuner other than [mine] is idle, so the DVR still finds one: gets only, stopping at the first idle one.
     * A tuner that doesn't answer counts as maybe idle, so a missed reply never gives the tuner back.
     */
    private suspend fun spareLeft(u: UnitRun, mine: Int): Boolean {
        for (t in 0 until u.device.tunerCount) {
            if (t == mine) continue
            when (u.sessions.occupancy(t)) {
                Occupancy.Idle, Occupancy.Unknown -> return true
                is Occupancy.Busy, Occupancy.DeviceBusy -> Unit
            }
        }
        return false
    }

    private sealed interface UnitHold {
        /** Held until the end of the session. */
        data object Done : UnitHold

        data object Refused : UnitHold

        /** The unit's last idle tuner was taken: the DVR needs it more, so this one went back. */
        data object GaveBack : UnitHold

        data class Lost(val tuner: Int, val reason: LostReason) : UnitHold
    }

    /**
     * One unit of a compare until [until] (null: until cancelled): a tuner is claimed only while another stays idle,
     * from the highest-numbered down (0 and 1 are the FLEX 4K's ATSC 3.0 tuners, best left to the DVR), or from 0 up
     * for a channel known to be ATSC 3.0. Waits when it can't spare one; a lost tuner tries another once, then waits.
     */
    private suspend fun holdUnit(u: UnitRun, until: Long?) {
        var unreachable = 0
        var lost: LostReason? = null
        var triedAnother = false
        var exclude: Int? = null
        while (timeLeft(until) > Duration.ZERO) {
            val held = leases.held.value
            setUnit(u) { UnitState.Claiming }
            val idle = idleTuners(u)
            val free = idle?.filter { it != exclude }.orEmpty()
            val result: UnitHold? = when {
                idle == null -> {
                    val at = clock.millis()
                    addFor(u, u.detector.missedPoll(at), at)
                    val wait = minOf(Backoff.delayAfter(++unreachable), timeLeft(until))
                    setUnit(u) { UnitState.Unreachable(wait) }
                    flush()
                    delay(wait)
                    null
                }
                idle.size < 2 || free.isEmpty() -> {
                    unreachable = 0
                    waitForSpare(u, lost, held, until)
                    exclude = null
                    null
                }
                else -> coroutineScope {
                    unreachable = 0
                    val order = if (u.channel.atsc3) free.sorted() else free.sortedDescending()
                    when (val claim = u.sessions.claim(this, u.channel.target, order)) {
                        is ClaimResult.Claimed -> {
                            lost = null
                            holdTuner(u, claim.tuner, until)
                        }
                        is ClaimResult.NoneFree -> {
                            waitForSpare(u, lost, held, until)
                            exclude = null
                            null
                        }
                        ClaimResult.UnknownChannel -> UnitHold.Refused
                        ClaimResult.Unreachable -> {
                            val at = clock.millis()
                            addFor(u, u.detector.missedPoll(at), at)
                            val wait = minOf(Backoff.delayAfter(++unreachable), timeLeft(until))
                            setUnit(u) { UnitState.Unreachable(wait) }
                            flush()
                            delay(wait)
                            null
                        }
                    }
                }
            }
            when (result) {
                null, UnitHold.GaveBack -> Unit
                UnitHold.Done -> return
                UnitHold.Refused -> throw ChannelRefused()
                is UnitHold.Lost -> {
                    val at = clock.millis()
                    addFor(u, u.detector.tunerLost(at, u.channel.guideNumber), at)
                    lost = result.reason
                    if (!triedAnother) {
                        triedAnother = true
                        exclude = result.tuner
                    } else {
                        setUnit(u) { UnitState.Paused(result.reason, busyRetry) }
                        flush()
                        delay(minOf(busyRetry, timeLeft(until)))
                    }
                }
            }
        }
    }

    /** No tuner to spare on [u]: its detector pauses (on purpose, so no gap) until one may be free. */
    private suspend fun waitForSpare(u: UnitRun, lost: LostReason?, held: Set<Pair<String, Int>>, until: Long?) {
        val at = clock.millis()
        addFor(u, u.detector.pause(at), at)
        setUnit(u) { lost?.let { UnitState.Paused(it, busyRetry) } ?: UnitState.NoSpare(busyRetry) }
        flush()
        leases.awaitChange(held, minOf(busyRetry, timeLeft(until)))
    }

    /** Holds [tuner] for [u] as hold mode's watch does, giving it back when the unit's last other idle tuner is taken. */
    private suspend fun holdTuner(u: UnitRun, tuner: ClaimedTuner, until: Long?): UnitHold {
        u.tuner = tuner.tuner
        val key = TunerKey(u.device.deviceId, tuner.tuner)
        errorCounter.retuned(key)
        leases.add(u.device.deviceId, tuner.tuner)
        val stream = tuner.hold is Hold.Stream
        setUnit(u) { UnitState.Holding(tuner.tuner, stream) }
        try {
            // Checked again now it's held: a recording may have taken the other idle tuner in the meantime.
            if (!spareLeft(u, tuner.tuner)) return UnitHold.GaveBack
            when (val waited = awaitLock(tuner)) {
                is LockWait.Lost -> return UnitHold.Lost(tuner.tuner, waited.reason)
                is LockWait.Locked -> {
                    val s = waited.status
                    val rf = RfChannels.of(s.channel)
                    sink.tuned(u.channel, rf?.number, rf?.centerHz, rf?.band?.name, (s.lock as? Lock.Locked)?.modulation == Modulation.ATSC3)
                    recordFor(u, tuner.tuner, s, tuner.debug())
                }
                is LockWait.NoLock -> waited.status?.let { recordFor(u, tuner.tuner, it, tuner.debug()) }
            }
            var missed = 0
            val interval = plan.pollIntervalMs.milliseconds
            var next = TimeSource.Monotonic.markNow() + interval
            while (true) {
                val wait = minOf(-next.elapsedNow(), timeLeft(until))
                if (wait > Duration.ZERO) delay(wait)
                if (timeLeft(until) <= Duration.ZERO) return UnitHold.Done
                next += interval
                if (next.hasPassedNow()) next = TimeSource.Monotonic.markNow() + interval
                when (val poll = tuner.poll()) {
                    is Poll.Reading -> {
                        missed = 0
                        recordFor(u, tuner.tuner, poll.status, tuner.debug())
                    }
                    is Poll.Unparseable -> {
                        missed = 0
                        buffer += ReadingEntity(
                            sessionId = sessionId, ts = clock.millis(), guideNumber = u.channel.guideNumber, tuner = tuner.tuner,
                            ss = 0, snq = 0, seq = 0, bps = null, pps = null, lock = LOCK_UNREAD, rawStatus = poll.raw, deviceId = u.device.deviceId,
                        )
                    }
                    is Poll.Unreachable -> {
                        val at = clock.millis()
                        addFor(u, u.detector.missedPoll(at), at)
                        missed++
                    }
                    is Poll.Lost -> return UnitHold.Lost(tuner.tuner, poll.reason)
                }
                if (!spareLeft(u, tuner.tuner)) return UnitHold.GaveBack
                val count = missed
                setUnit(u) { UnitState.Holding(tuner.tuner, stream, count) }
                if (sinceFlush.elapsedNow() >= flushEvery) flush()
            }
        } finally {
            withContext(NonCancellable) {
                tuner.release()
                leases.remove(u.device.deviceId, tuner.tuner)
                val at = clock.millis()
                addFor(u, u.detector.pause(at), at)
            }
        }
    }

    private fun setUnit(u: UnitRun, state: () -> UnitState) = publish { s ->
        s.copy(units = s.units.map { if (it.deviceId == u.device.deviceId) it.copy(state = state()) else it })
    }

    /** Buffers one reading of a compare's unit, feeds its detector, and adds it to its line. */
    private fun recordFor(u: UnitRun, tuner: Int, s: TunerStatus, debug: TunerDebug?) {
        val at = clock.millis()
        val sample = s.sample()
        buffer += reading(u.channel, tuner, s, debug, at).copy(deviceId = u.device.deviceId)
        val added = debug?.let { errorCounter.add(TunerKey(u.device.deviceId, tuner), u.channel.guideNumber, it.counters()) }
        addFor(u, u.detector.reading(at, u.channel.guideNumber, sample), at)
        val good = plan.thresholds.assess(sample).health == Health.GOOD
        publish {
            it.copy(
                readings = it.readings + 1,
                goodReadings = it.goodReadings + if (good) 1 else 0,
                errors = added?.let { a -> it.errors + a } ?: it.errors,
                countersRead = it.countersRead || added != null,
                units = it.units.map { c -> if (c.deviceId == u.device.deviceId) c.copy(timeline = c.timeline + (at to sample), latest = s) else c },
                open = unitsOpen(),
            )
        }
    }

    /** A compare's unit event: its device, and for its own (not unreachable or gap) the tuner it held. */
    private fun SignalEvent.on(u: UnitRun) = when (kind) {
        EventKind.DEVICE_UNREACHABLE, EventKind.GAP -> copy(deviceId = u.device.deviceId)
        else -> copy(deviceId = u.device.deviceId, tuner = u.tuner)
    }

    private fun unitsOpen() = units.flatMap { u -> u.detector.open.map { it.on(u) } }.sortedBy { it.startMs }

    /** [add] for a compare's unit: its events tagged with where they happened. */
    private fun addFor(u: UnitRun, events: List<SignalEvent>, at: Long) {
        if (events.isEmpty()) return
        val tagged = events.map { it.on(u) }
        finished += tagged
        publish { it.copy(events = it.events + tagged, open = unitsOpen()) }
        if (plan.notifyDips) tagged.forEach { e -> alerts.onEvent(e, at)?.let(onAlert) }
    }

    /** One device of a whole-device watch: its tuners' observers on its one control connection, and its watch. */
    private class WatchedDevice(val device: Device, val observers: List<TunerObserver>, val watch: DeviceWatch, val lineup: List<Channel>) {
        val byGuide = lineup.associateBy { it.guideNumber }
        var missed = 0
    }

    /**
     * A whole-device watch (SPEC Screens › Monitor setup, "Watch every tuner"): every interval, every tuner of every
     * planned device is read without being claimed, gets only, on one control connection per device. The devices
     * are read side by side, so one that stops answering doesn't hold up the others; each device's [DeviceWatch]
     * turns its reads into readings to store and events, a dip detector and error baseline per tuner.
     */
    private suspend fun observeAll(startedAt: Long): MonitorEnd {
        val deadline = plan.durationMs?.let { startedAt + it }
        val units = plan.wholeDevices.map { d ->
            val s = if (d.deviceId == plan.device.deviceId) sessions else sessionsOf(d)
            WatchedDevice(d, (0 until d.tunerCount).map { s.observer(it) }, DeviceWatch(d.deviceId, plan.thresholds), plan.channels.filter { it.deviceId == d.deviceId })
        }
        deviceWatches += units.map { it.watch }
        val seen = LinkedHashSet<String>()
        val holders = LinkedHashSet<String>()
        val interval = plan.pollIntervalMs.milliseconds
        var next = TimeSource.Monotonic.markNow()
        while (true) {
            val wait = minOf(-next.elapsedNow(), timeLeft(deadline))
            if (wait > Duration.ZERO) delay(wait)
            if (timeLeft(deadline) <= Duration.ZERO) return MonitorEnd.TIME_UP
            next += interval
            if (next.hasPassedNow()) next = TimeSource.Monotonic.markNow() + interval
            val reads = coroutineScope { units.map { u -> async { readAll(u) } }.awaitAll() }
            var changed = false
            for ((u, observations) in units.zip(reads)) {
                val at = clock.millis()
                if (observations.none { it !is Observation.Unreachable }) {
                    u.missed++
                    add(u.watch.missed(at), at)
                    setUses(u.device.deviceId) { _, _ -> WatchedUse.NoAnswer }
                    continue
                }
                u.missed = 0
                val channels = observations.map { o -> (o as? Observation.InUse)?.let { channelOf(it, u.byGuide, u.lineup, u.device.deviceId) } }
                val step = u.watch.polled(
                    at,
                    observations.withIndex().associate { (t, o) ->
                        t to when (o) {
                            is Observation.InUse -> TunerRead.Tuned(channels[t]!!.guideNumber, o.status.sample(), o.debug?.counters())
                            Observation.Idle -> TunerRead.Idle
                            is Observation.Unparseable, is Observation.Unreachable -> TunerRead.Unread
                        }
                    },
                )
                for ((t, added) in step.recorded) {
                    val o = observations[t] as Observation.InUse
                    store(u.device.deviceId, channels[t]!!, t, o.status, o.debug, added, at)
                }
                for (t in step.firstLocked) {
                    val c = channels[t]!!
                    val status = (observations[t] as Observation.InUse).status
                    if (c.guideNumber in u.byGuide && status.isLocked) {
                        val rf = RfChannels.of(status.channel)
                        sink.tuned(c, rf?.number, rf?.centerHz, rf?.band?.name, (status.lock as? Lock.Locked)?.modulation == Modulation.ATSC3)
                    }
                }
                for (t in step.retuned) changed = seen.add(channels[t]!!.guideNumber) || changed
                for (o in observations) (o as? Observation.InUse)?.holder?.let { changed = holders.add(it) || changed }
                add(step.events, at)
                setUses(u.device.deviceId) { t, before ->
                    when (val o = observations[t]) {
                        is Observation.InUse -> WatchedUse.InUse(o.holder, channels[t]!!, o.status.takeIf { t in step.recorded })
                        Observation.Idle -> WatchedUse.Idle
                        is Observation.Unparseable, is Observation.Unreachable -> if (before is WatchedUse.InUse) before.copy(latest = null) else WatchedUse.NoAnswer
                    }
                }
            }
            if (changed) log.watchedAll(sessionId, seen.toList(), holders.toList())
            val unreachable = units.filter { it.missed > 0 }.map { it.device.deviceId }.toSet()
            publish { it.copy(phase = MonitorPhase.WatchingAll(unreachable), open = units.flatMap { u -> u.watch.open }) }
            if (sinceFlush.elapsedNow() >= flushEvery) flush()
        }
    }

    /**
     * Reads every tuner of [u], gets only. When the first read gets no answer the device is taken as not answering
     * this poll and the rest aren't tried, so a device off the network costs one timeout a poll, not one a tuner.
     */
    private suspend fun readAll(u: WatchedDevice): List<Observation> {
        val out = ArrayList<Observation>(u.observers.size)
        for (o in u.observers) {
            if (out.isNotEmpty() && out.all { it is Observation.Unreachable }) {
                out += out.first()
                continue
            }
            out += o.read()
        }
        return out
    }

    private fun setUses(deviceId: String, use: (tuner: Int, before: WatchedUse) -> WatchedUse) = publish { s ->
        s.copy(tuners = s.tuners.map { if (it.deviceId == deviceId) it.copy(use = use(it.tuner, it.use)) else it })
    }

    /** Buffers one reading of a whole-device watch and counts it; [added] is the errors it added, null without counters. */
    private fun store(deviceId: String, channel: Channel, tuner: Int, s: TunerStatus, debug: TunerDebug?, added: PacketErrors?, at: Long) {
        buffer += reading(channel, tuner, s, debug, at).copy(deviceId = deviceId)
        val good = plan.thresholds.assess(s.sample()).health == Health.GOOD
        publish {
            it.copy(
                readings = it.readings + 1,
                goodReadings = it.goodReadings + if (good) 1 else 0,
                errors = added?.let { a -> it.errors + a } ?: it.errors,
                countersRead = it.countersRead || added != null,
            )
        }
    }

    /**
     * The lineup channel the client tuned: by guide number, else by the frequency or RF channel the tuner says.
     * One the lineup doesn't have is named by the tuner's own `ch=` value.
     */
    private fun channelOf(
        o: Observation.InUse,
        byGuide: Map<String, Channel>,
        lineup: List<Channel> = plan.channels,
        deviceId: String = plan.device.deviceId,
    ): Channel {
        o.guideNumber?.let { g -> return byGuide[g] ?: unknownChannel(g, deviceId) }
        val rf = RfChannels.of(o.status.channel)?.number
        val match = rf?.let { n -> lineup.firstOrNull { it.rfChannel == n } }
        return match ?: unknownChannel(o.status.raw.substringAfter("ch=", "").substringBefore(' ').ifEmpty { o.status.raw }, deviceId)
    }

    private fun unknownChannel(guideNumber: String, deviceId: String = plan.device.deviceId) =
        Channel(deviceId, guideNumber, "", "", null, null, null, false)

    private fun TunerStatus.sample() = SignalSample(isLocked, ss, snq, seq)

    private fun TunerDebug.counters() = ErrorCounters(transportErrors, crcErrors, resync, networkErrors)

    private fun reading(channel: Channel, tuner: Int, s: TunerStatus, debug: TunerDebug?, at: Long) = ReadingEntity(
        sessionId = sessionId, ts = at, guideNumber = channel.guideNumber, tuner = tuner,
        ss = s.ss, snq = s.snq, seq = s.seq, bps = s.bps, pps = s.pps,
        lock = when (val l = s.lock) {
            Lock.None -> LOCK_NONE
            is Lock.Locked -> l.raw
        },
        transportErrors = debug?.transportErrors, crcErrors = debug?.crcErrors,
        resyncs = debug?.resync, networkErrors = debug?.networkErrors,
    )

    private fun record(channel: Channel, tuner: Int, s: TunerStatus, debug: TunerDebug?) {
        val at = clock.millis()
        val sample = s.sample()
        buffer += reading(channel, tuner, s, debug, at)
        val added = debug?.let { errorCounter.add(tuner, channel.guideNumber, it.counters()) }
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

    /**
     * The phone's Wi-Fi for the whole session: each state that differs from the last, stamped as it comes. A Wi-Fi
     * source that fails costs the samples, never the session.
     */
    private suspend fun recordWifi() {
        wifi.states().distinctUntilChanged().catch { }.collect { s ->
            val sample = WifiSample(clock.millis(), s.connected, s.rssiDbm, s.linkMbps, s.frequencyMhz, s.bssid)
            synchronized(wifiBuffer) { wifiBuffer += sample }
        }
    }

    private suspend fun flush() {
        sinceFlush = TimeSource.Monotonic.markNow()
        val samples = synchronized(wifiBuffer) { wifiBuffer.toList().also { wifiBuffer.clear() } }
        if (buffer.isEmpty() && finished.isEmpty() && samples.isEmpty()) return
        val readings = buffer.toList()
        val events = finished.toList()
        buffer.clear()
        finished.clear()
        log.append(readings, events, samples, sessionId)
    }

    private companion object {
        val OWN_RELEASE_WAIT = 3.seconds

        /** The `lock` value stored for a reading in good order but with no lock. */
        const val LOCK_NONE = "none"

        /** The `lock` value of a reading whose line couldn't be parsed; its [ReadingEntity.rawStatus] has the line. */
        const val LOCK_UNREAD = ""
    }
}
