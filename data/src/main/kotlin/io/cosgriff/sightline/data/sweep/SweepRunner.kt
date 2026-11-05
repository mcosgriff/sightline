package io.cosgriff.sightline.data.sweep

import io.cosgriff.sightline.data.device.Channel
import io.cosgriff.sightline.data.device.TunerLeases
import io.cosgriff.sightline.data.live.TunedChannelSink
import io.cosgriff.sightline.protocol.ClaimResult
import io.cosgriff.sightline.protocol.ClaimedTuner
import io.cosgriff.sightline.protocol.Lock
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
import kotlin.time.TimeSource

/** One reading taken during a sweep. */
data class SweepReading(val atMs: Long, val guideNumber: String, val tuner: Int, val status: TunerStatus)

/** Where a running sweep is with the channel it is on. */
sealed interface SweepPhase {
    data object Claiming : SweepPhase

    /** Holding [tuner] and reading it; [latest] is the last reading. */
    data class Measuring(val tuner: Int, val latest: TunerStatus?) : SweepPhase

    /** Every tuner is held by another client; tried again when one frees or every [retryEvery]. */
    data class AllBusy(val occupancy: Map<Int, Occupancy>, val retryEvery: Duration) : SweepPhase

    /** The device didn't answer; tried again shortly. */
    data object Unreachable : SweepPhase
}

/** A sweep in progress: channel [index] of [total], and the share of the whole sweep done, 0 to 1. */
data class SweepProgress(val index: Int, val total: Int, val channel: Channel, val phase: SweepPhase, val fraction: Float)

sealed interface SweepOutcome {
    /** [channels] are the guide numbers tried, in order; those that couldn't be tuned have no readings. */
    data class Finished(val startedAt: Long, val endedAt: Long, val channels: List<String>, val readings: List<SweepReading>) : SweepOutcome

    /** The device stopped answering. Nothing is saved. */
    data object Unreachable : SweepOutcome
}

/**
 * Sweep's loop (SPEC Screens › Sweep): each channel in turn on an idle tuner for [dwell], counted from the tune,
 * reading every [pollInterval] after [settle] (the first second after a tune reads seq 0). Each channel is claimed
 * and released on its own, the last tuner first, so a DVR is never kept from a tuner for longer than one dwell.
 *
 * A channel whose RF channel is already known to have been measured in this sweep is skipped: subchannels share
 * the signal. A channel no idle tuner can take (ATSC 3.0 when only the ATSC 1.0 tuners are free) or that the
 * device refuses is tried and left without readings. When every tuner is busy the sweep waits, as Live does.
 */
class SweepRunner(
    private val deviceId: String,
    private val sessions: TunerSessions,
    private val leases: TunerLeases,
    private val sink: TunedChannelSink,
    private val clock: Clock,
    private val pollInterval: Duration,
    private val dwell: Duration,
    private val settle: Duration = 1.seconds,
    private val busyRetry: Duration = 30.seconds,
    private val unreachableRetry: Duration = 5.seconds,
    private val maxUnreachable: Int = 3,
) {
    private val _progress = MutableStateFlow<SweepProgress?>(null)
    val progress: StateFlow<SweepProgress?> = _progress.asStateFlow()

    private var lastTuner: Int? = null

    /** Runs the whole sweep. Cancelling it releases the tuner in hand and returns nothing. */
    suspend fun run(channels: List<Channel>, tunerCount: Int): SweepOutcome {
        val startedAt = clock.millis()
        val readings = mutableListOf<SweepReading>()
        val tried = mutableListOf<String>()
        val measuredRf = mutableSetOf<Int>()
        for ((index, channel) in channels.withIndex()) {
            if (channel.rfChannel != null && channel.rfChannel in measuredRf) continue
            tried += channel.guideNumber
            val ok = sweepOne(Position(index, channels.size, channel), tunerCount) { reading ->
                readings += reading
                RfChannels.of(reading.status.channel)?.let { measuredRf += it.number }
            }
            if (!ok) return SweepOutcome.Unreachable
        }
        return SweepOutcome.Finished(startedAt, clock.millis(), tried, readings)
    }

    private data class Position(val index: Int, val total: Int, val channel: Channel)

    private fun publish(at: Position, phase: SweepPhase, dwellFraction: Float = 0f) {
        _progress.value = SweepProgress(at.index, at.total, at.channel, phase, (at.index + dwellFraction) / at.total)
    }

    /** False when the device stopped answering. */
    private suspend fun sweepOne(at: Position, tunerCount: Int, record: (SweepReading) -> Unit): Boolean {
        var unreachable = 0
        var waitedForOwn = false
        while (true) {
            publish(at, SweepPhase.Claiming)
            val order = (0 until tunerCount).sortedBy { if (it == lastTuner) -1 else it }
            val next: Step = coroutineScope {
                when (val claim = sessions.claim(this, at.channel.target, order)) {
                    is ClaimResult.Claimed -> {
                        val tuner = claim.tuner
                        lastTuner = tuner.tuner
                        leases.add(deviceId, tuner.tuner)
                        try {
                            measure(at, tuner, record)
                        } finally {
                            tuner.release()
                            leases.remove(deviceId, tuner.tuner)
                        }
                        Step.NEXT_CHANNEL
                    }
                    // An idle tuner that still can't take it: this channel needs one of the busy ones. Move on.
                    is ClaimResult.NoneFree if claim.occupancy.values.any { it == Occupancy.Idle } -> Step.NEXT_CHANNEL
                    is ClaimResult.NoneFree -> {
                        val held = leases.held.value
                        if (leases.holdsAny(deviceId) && !waitedForOwn) {
                            waitedForOwn = true
                            leases.awaitChange(held, OWN_RELEASE_WAIT)
                        } else {
                            publish(at, SweepPhase.AllBusy(claim.occupancy, busyRetry))
                            leases.awaitChange(held, busyRetry)
                        }
                        Step.RETRY
                    }
                    ClaimResult.UnknownChannel -> Step.NEXT_CHANNEL
                    ClaimResult.Unreachable -> {
                        if (++unreachable >= maxUnreachable) {
                            Step.GIVE_UP
                        } else {
                            publish(at, SweepPhase.Unreachable)
                            delay(unreachableRetry)
                            Step.RETRY
                        }
                    }
                }
            }
            when (next) {
                Step.NEXT_CHANNEL -> return true
                Step.GIVE_UP -> return false
                Step.RETRY -> Unit
            }
        }
    }

    private enum class Step { NEXT_CHANNEL, RETRY, GIVE_UP }

    private suspend fun measure(at: Position, tuner: ClaimedTuner, record: (SweepReading) -> Unit) {
        val start = TimeSource.Monotonic.markNow()
        fun fraction() = (start.elapsedNow() / dwell).toFloat().coerceIn(0f, 1f)
        publish(at, SweepPhase.Measuring(tuner.tuner, null))
        delay(settle)
        var recorded = false
        while (start.elapsedNow() < dwell) {
            when (val poll = tuner.poll()) {
                is Poll.Reading -> {
                    val s = poll.status
                    record(SweepReading(clock.millis(), at.channel.guideNumber, tuner.tuner, s))
                    if (!recorded && s.isLocked) {
                        recorded = true
                        val rf = RfChannels.of(s.channel)
                        val atsc3 = (s.lock as? Lock.Locked)?.modulation == Modulation.ATSC3
                        sink.tuned(at.channel, rf?.number, rf?.centerHz, rf?.band?.name, atsc3)
                    }
                    publish(at, SweepPhase.Measuring(tuner.tuner, s), fraction())
                }
                // A line it can't read, or a missed answer: nothing to count, keep going.
                is Poll.Unparseable, is Poll.Unreachable -> Unit
                // Taken by a DVR mid-dwell: keep what was read and move on.
                is Poll.Lost -> return
            }
            delay(minOf(pollInterval, (dwell - start.elapsedNow()).coerceAtLeast(Duration.ZERO)))
        }
        publish(at, SweepPhase.Measuring(tuner.tuner, null), 1f)
    }

    private companion object {
        val OWN_RELEASE_WAIT = 3.seconds
    }
}
