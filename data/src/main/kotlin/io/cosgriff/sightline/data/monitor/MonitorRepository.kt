package io.cosgriff.sightline.data.monitor

import io.cosgriff.sightline.analysis.SignalEvent
import io.cosgriff.sightline.analysis.WifiSample
import io.cosgriff.sightline.data.db.Converters
import io.cosgriff.sightline.data.db.EventEntity
import io.cosgriff.sightline.data.db.ReadingEntity
import io.cosgriff.sightline.data.db.SessionDao
import io.cosgriff.sightline.data.db.SessionEntity
import io.cosgriff.sightline.data.db.SessionKind
import io.cosgriff.sightline.data.db.WifiSampleEntity
import javax.inject.Inject
import javax.inject.Singleton

/** Monitor sessions in Room: `MONITOR` sessions with their readings, events and the phone's Wi-Fi. */
@Singleton
class MonitorRepository @Inject constructor(private val sessions: SessionDao) : MonitorLog {
    override suspend fun begin(plan: MonitorPlan, startedAt: Long): Long = sessions.insert(
        SessionEntity(
            deviceId = plan.device.deviceId,
            kind = SessionKind.MONITOR,
            startedAt = startedAt,
            endedAt = null,
            channels = if (plan.watch || plan.wholeDevice) emptyList() else plan.channels.map { it.guideNumber }.distinct(),
            tuner = plan.tuner,
            pollIntervalMs = plan.pollIntervalMs,
            dwellMs = if (plan.rotate) plan.dwellMs else null,
            fetchWeather = plan.fetchWeather,
            firmware = plan.device.firmware.ifEmpty { null },
            watched = plan.watch || plan.wholeDevice,
            wholeDevice = plan.wholeDevice,
            // A two-unit compare is told by its two devices without a whole-device watch (SessionInfo.compare).
            devices = plan.wholeDevices.ifEmpty { plan.compareUnits }.map { it.deviceId },
        ),
    )

    override suspend fun append(readings: List<ReadingEntity>, events: List<SignalEvent>, sessionId: Long) =
        sessions.append(readings.map { it.copy(sessionId = sessionId) }, events.map { it.toEntity(sessionId) })

    override suspend fun append(readings: List<ReadingEntity>, events: List<SignalEvent>, wifi: List<WifiSample>, sessionId: Long) =
        sessions.appendWithWifi(
            readings.map { it.copy(sessionId = sessionId) },
            events.map { it.toEntity(sessionId) },
            wifi.map { WifiSampleEntity(0, sessionId, it.atMs, it.connected, it.rssiDbm, it.linkMbps, it.frequencyMhz, it.bssid) },
        )

    override suspend fun watched(sessionId: Long, channels: List<String>, holder: String?) =
        sessions.setWatched(sessionId, Converters().fromList(channels), holder)

    override suspend fun watchedAll(sessionId: Long, channels: List<String>, holders: List<String>) {
        val converters = Converters()
        sessions.setWatched(sessionId, converters.fromList(channels), holders.takeIf { it.isNotEmpty() }?.let(converters::fromList))
    }

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
        SignalEvent(it.kind, it.guideNumber, it.startTs, it.endTs, it.minSnq, it.minSeq, it.noLockMs, it.deviceId, it.tuner)
    }

    private fun SignalEvent.toEntity(sessionId: Long) =
        EventEntity(0, sessionId, startMs, endMs, guideNumber, kind, minSnq, minSeq, noLockMs, deviceId, tuner)
}
