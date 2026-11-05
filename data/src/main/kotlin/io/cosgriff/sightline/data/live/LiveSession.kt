package io.cosgriff.sightline.data.live

import io.cosgriff.sightline.analysis.LiveTrace
import io.cosgriff.sightline.analysis.SignalSample
import io.cosgriff.sightline.analysis.TimedSample
import io.cosgriff.sightline.data.device.Channel
import io.cosgriff.sightline.data.device.TunerLeases
import io.cosgriff.sightline.protocol.ClaimResult
import io.cosgriff.sightline.protocol.Hold
import io.cosgriff.sightline.protocol.Lock
import io.cosgriff.sightline.protocol.LostReason
import io.cosgriff.sightline.protocol.Modulation
import io.cosgriff.sightline.protocol.Occupancy
import io.cosgriff.sightline.protocol.Poll
import io.cosgriff.sightline.protocol.RfChannels
import io.cosgriff.sightline.protocol.TunerSessions
import io.cosgriff.sightline.protocol.TunerStatus
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What Live is doing. */
sealed interface LiveState {
    val channel: Channel

    /** Looking for an idle tuner and tuning it. */
    data class Claiming(override val channel: Channel) : LiveState

    /**
     * Holding [tuner] and polling it. [status] is the latest reading (null before the first), [raw] a reply that
     * couldn't be parsed, [missedPolls] how many polls in a row got no answer.
     */
    data class Watching(
        override val channel: Channel,
        val tuner: Int,
        val stream: Boolean,
        val status: TunerStatus? = null,
        val trace: LiveTrace = LiveTrace(),
        val raw: String? = null,
        val missedPolls: Int = 0,
    ) : LiveState

    /** No idle tuner can take this channel; who holds each. Tried again every [retryEvery]. */
    data class AllBusy(override val channel: Channel, val occupancy: Map<Int, Occupancy>, val retryEvery: Duration) : LiveState

    /** The tuner was taken or retuned by another client. Live stops; the user starts it again. */
    data class Lost(override val channel: Channel, val tuner: Int, val reason: LostReason) : LiveState

    /** The device refused the channel. */
    data class UnknownChannel(override val channel: Channel) : LiveState

    /** The device didn't answer the claim. Tried again shortly. */
    data class Unreachable(override val channel: Channel) : LiveState
}

/** Called once per tune with what the first locked reading revealed, to fill in the channel's RF details. */
fun interface TunedChannelSink {
    suspend fun tuned(channel: Channel, rf: Int?, hz: Long?, band: String?, atsc3: Boolean)
}

/**
 * Live's loop for one device (SPEC Screens › Live): claim an idle tuner for a channel, poll it every
 * [pollInterval], release it when [run] is cancelled. Never takes a busy tuner; when all are busy it says who
 * holds each and retries every 30 s (SPEC Edge cases).
 */
class LiveSession(
    private val deviceId: String,
    private val sessions: TunerSessions,
    private val leases: TunerLeases,
    private val sink: TunedChannelSink,
    private val clock: Clock,
    private val pollInterval: Duration,
    private val busyRetry: Duration = 30.seconds,
    private val unreachableRetry: Duration = 5.seconds,
) {
    private val _state = MutableStateFlow<LiveState?>(null)
    val state: StateFlow<LiveState?> = _state.asStateFlow()

    /** The tuner held last, tried first on the next channel so changing channel keeps the same one. */
    var lastTuner: Int? = null
        private set

    /** Runs until cancelled or the tuner is lost. Always releases what it claimed. */
    suspend fun run(channel: Channel, tunerCount: Int) {
        while (true) {
            _state.value = LiveState.Claiming(channel)
            val order = (0 until tunerCount).sortedBy { if (it == lastTuner) -1 else it }
            val stop = coroutineScope {
                when (val claim = sessions.claim(this, channel.target, order)) {
                    is ClaimResult.Claimed -> {
                        val tuner = claim.tuner
                        lastTuner = tuner.tuner
                        leases.add(deviceId, tuner.tuner)
                        try {
                            watch(channel, tuner)
                        } finally {
                            tuner.release()
                            leases.remove(deviceId, tuner.tuner)
                        }
                        true
                    }
                    is ClaimResult.NoneFree -> {
                        _state.value = LiveState.AllBusy(channel, claim.occupancy, busyRetry)
                        delay(busyRetry)
                        false
                    }
                    ClaimResult.UnknownChannel -> {
                        _state.value = LiveState.UnknownChannel(channel)
                        true
                    }
                    ClaimResult.Unreachable -> {
                        _state.value = LiveState.Unreachable(channel)
                        delay(unreachableRetry)
                        false
                    }
                }
            }
            if (stop) return
        }
    }

    private suspend fun watch(channel: Channel, tuner: io.cosgriff.sightline.protocol.ClaimedTuner) {
        var watching = LiveState.Watching(channel, tuner.tuner, stream = tuner.hold is Hold.Stream)
        _state.value = watching
        var recorded = false
        while (true) {
            watching = when (val poll = tuner.poll()) {
                is Poll.Reading -> {
                    val s = poll.status
                    if (!recorded && s.isLocked) {
                        recorded = true
                        val rf = RfChannels.of(s.channel)
                        val atsc3 = (s.lock as? Lock.Locked)?.modulation == Modulation.ATSC3
                        sink.tuned(channel, rf?.number, rf?.centerHz, rf?.band?.name, atsc3)
                    }
                    val sample = SignalSample(s.isLocked, s.ss, s.snq, s.seq)
                    watching.copy(status = s, trace = watching.trace + TimedSample(clock.millis(), sample), raw = null, missedPolls = 0)
                }
                is Poll.Unparseable -> watching.copy(raw = poll.raw, missedPolls = 0)
                is Poll.Unreachable -> watching.copy(missedPolls = watching.missedPolls + 1)
                is Poll.Lost -> {
                    _state.value = LiveState.Lost(channel, tuner.tuner, poll.reason)
                    return
                }
            }
            _state.value = watching
            delay(pollInterval)
        }
    }
}
