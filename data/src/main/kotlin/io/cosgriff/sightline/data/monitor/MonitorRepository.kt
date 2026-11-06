package io.cosgriff.sightline.data.monitor

import io.cosgriff.sightline.analysis.SignalEvent
import io.cosgriff.sightline.data.db.EventEntity
import io.cosgriff.sightline.data.db.ReadingEntity
import io.cosgriff.sightline.data.db.SessionDao
import io.cosgriff.sightline.data.db.SessionEntity
import io.cosgriff.sightline.data.db.SessionKind
import javax.inject.Inject
import javax.inject.Singleton

/** Monitor sessions in Room: `MONITOR` sessions with their readings and events. */
@Singleton
class MonitorRepository @Inject constructor(private val sessions: SessionDao) : MonitorLog {
    override suspend fun begin(plan: MonitorPlan, startedAt: Long): Long = sessions.insert(
        SessionEntity(
            deviceId = plan.device.deviceId,
            kind = SessionKind.MONITOR,
            startedAt = startedAt,
            endedAt = null,
            channels = plan.channels.map { it.guideNumber },
            tuner = plan.tuner,
            pollIntervalMs = plan.pollIntervalMs,
            dwellMs = if (plan.rotate) plan.dwellMs else null,
            fetchWeather = plan.fetchWeather,
            firmware = plan.device.firmware.ifEmpty { null },
        ),
    )

    override suspend fun append(readings: List<ReadingEntity>, events: List<SignalEvent>, sessionId: Long) =
        sessions.append(readings.map { it.copy(sessionId = sessionId) }, events.map { it.toEntity(sessionId) })

    override suspend fun end(sessionId: Long, endedAt: Long, tuner: Int?) = sessions.finish(sessionId, endedAt, tuner)

    /**
     * Ends the monitor sessions a killed process left open, at their last reading. The monitor service is not
     * restarted by the system, so at app start no session is really running.
     */
    suspend fun closeAbandoned() {
        for (s in sessions.unfinished(SessionKind.MONITOR)) {
            sessions.finish(s.id, sessions.lastReadingTs(s.id) ?: s.startedAt, s.tuner)
        }
    }

    suspend fun events(sessionId: Long): List<SignalEvent> = sessions.events(sessionId).map {
        SignalEvent(it.kind, it.guideNumber, it.startTs, it.endTs, it.minSnq, it.minSeq, it.noLockMs)
    }

    private fun SignalEvent.toEntity(sessionId: Long) =
        EventEntity(0, sessionId, startMs, endMs, guideNumber, kind, minSnq, minSeq, noLockMs)
}
