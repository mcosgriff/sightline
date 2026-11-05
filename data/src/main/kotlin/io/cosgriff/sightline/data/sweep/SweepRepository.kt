package io.cosgriff.sightline.data.sweep

import io.cosgriff.sightline.analysis.SignalSample
import io.cosgriff.sightline.analysis.SweepSummary
import io.cosgriff.sightline.analysis.SweptChannel
import io.cosgriff.sightline.analysis.Thresholds
import io.cosgriff.sightline.data.db.ChannelDao
import io.cosgriff.sightline.data.db.ReadingEntity
import io.cosgriff.sightline.data.db.SessionDao
import io.cosgriff.sightline.data.db.SessionEntity
import io.cosgriff.sightline.data.db.SessionKind
import io.cosgriff.sightline.data.device.Channel
import io.cosgriff.sightline.data.device.toChannel
import io.cosgriff.sightline.protocol.Lock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** A saved sweep as lists show it. */
data class SweepInfo(val id: Long, val deviceId: String, val startedAt: Long, val endedAt: Long?, val channelCount: Int, val dwellMs: Long?)

/**
 * A saved sweep, summarised. [channels] holds what the lineup knows about each guide number (name, RF channel,
 * band); a channel since dropped from the lineup is missing from it.
 */
data class SavedSweep(val info: SweepInfo, val summary: SweepSummary, val channels: Map<String, Channel>)

/** Sweeps are `SWEEP` sessions with their readings (SPEC Screens › Sweep). */
@Singleton
class SweepRepository @Inject constructor(
    private val sessions: SessionDao,
    private val channels: ChannelDao,
) {
    suspend fun save(deviceId: String, outcome: SweepOutcome.Finished, dwellMs: Long, pollIntervalMs: Long): Long {
        val session = SessionEntity(
            deviceId = deviceId,
            kind = SessionKind.SWEEP,
            startedAt = outcome.startedAt,
            endedAt = outcome.endedAt,
            channels = outcome.channels,
            tuner = outcome.readings.map { it.tuner }.distinct().singleOrNull(),
            pollIntervalMs = pollIntervalMs,
            dwellMs = dwellMs,
        )
        val readings = outcome.readings.map { r ->
            val s = r.status
            ReadingEntity(
                sessionId = 0,
                ts = r.atMs,
                guideNumber = r.guideNumber,
                tuner = r.tuner,
                ss = s.ss,
                snq = s.snq,
                seq = s.seq,
                bps = s.bps,
                pps = s.pps,
                lock = when (val l = s.lock) {
                    Lock.None -> LOCK_NONE
                    is Lock.Locked -> l.raw
                },
            )
        }
        return sessions.insertWithReadings(session, readings)
    }

    /** The device's sweeps, newest first. */
    fun sweeps(deviceId: String): Flow<List<SweepInfo>> =
        sessions.observe(deviceId, SessionKind.SWEEP).map { list -> list.map { it.info() } }

    suspend fun load(id: Long, thresholds: Thresholds): SavedSweep? {
        val session = sessions.get(id)?.takeIf { it.kind == SessionKind.SWEEP } ?: return null
        val byChannel = sessions.readings(id).groupBy { it.guideNumber }
        val swept = session.channels.map { guide ->
            val samples = byChannel[guide].orEmpty().map { SignalSample(it.lock != LOCK_NONE, it.ss, it.snq, it.seq) }
            SweptChannel.of(guide, samples, thresholds)
        }
        val known = channels.list(session.deviceId).associate { it.guideNumber to it.toChannel() }
        return SavedSweep(session.info(), SweepSummary(swept), known.filterKeys { it in session.channels })
    }

    suspend fun delete(id: Long) = sessions.delete(id)

    private fun SessionEntity.info() = SweepInfo(id, deviceId, startedAt, endedAt, channels.size, dwellMs)

    private companion object {
        const val LOCK_NONE = "none"
    }
}
