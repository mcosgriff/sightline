package io.cosgriff.sightline.data.session

import io.cosgriff.sightline.analysis.ChannelInfo
import io.cosgriff.sightline.analysis.Diagnosis
import io.cosgriff.sightline.analysis.Finding
import io.cosgriff.sightline.analysis.Health
import io.cosgriff.sightline.analysis.LoggedReading
import io.cosgriff.sightline.analysis.SessionData
import io.cosgriff.sightline.analysis.ErrorCounters
import io.cosgriff.sightline.analysis.SessionStats
import io.cosgriff.sightline.analysis.SignalEvent
import io.cosgriff.sightline.analysis.SignalSample
import io.cosgriff.sightline.analysis.Thresholds
import io.cosgriff.sightline.analysis.WeatherHour
import io.cosgriff.sightline.analysis.sparklineOf
import io.cosgriff.sightline.data.db.ReadingEntity
import io.cosgriff.sightline.data.db.SessionDao
import io.cosgriff.sightline.data.db.SessionEntity
import io.cosgriff.sightline.data.db.SessionKind
import io.cosgriff.sightline.data.db.SessionSummaryEntity
import io.cosgriff.sightline.data.db.WeatherHourEntity
import io.cosgriff.sightline.data.device.Channel
import io.cosgriff.sightline.data.device.DemoDevice
import io.cosgriff.sightline.data.device.DeviceRepository
import io.cosgriff.sightline.data.settings.SettingsRepository
import io.cosgriff.sightline.data.weather.WeatherSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.ZoneId
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton

/** A saved session's facts. */
data class SessionInfo(
    val id: Long,
    val deviceId: String,
    val kind: SessionKind,
    val startedAt: Long,
    val endedAt: Long?,
    val channels: List<String>,
    val tuner: Int?,
    val pollIntervalMs: Long,
    val note: String?,
    /** The device's firmware when the session started; null when not kept (older sessions). */
    val firmware: String? = null,
    /** Read a tuner another client was using, without holding it; [holder] is that client's address. */
    val watched: Boolean = false,
    val holder: String? = null,
)

/** What History shows of a session; null figures before it's summarised. */
data class SessionSummary(
    val readings: Int,
    val goodShare: Double?,
    val dips: Int,
    val medianSnq: Int?,
    val health: Health?,
    val sparkline: List<Int?>,
)

data class HistoryRow(val info: SessionInfo, val summary: SessionSummary?)

/** Where a session's weather stands. */
sealed interface SessionWeather {
    /** Weather wasn't asked for when the session was set up. */
    data object NotAsked : SessionWeather

    /** Asked for, but no home is set to fetch it for. */
    data object NoHome : SessionWeather

    /** Asked for and not had yet (offline, or Open-Meteo failed): it can be tried again. */
    data object Missing : SessionWeather

    /** [demo]: made up for the Demo tuner, not real weather. */
    data class Fetched(val hours: List<WeatherHour>, val demo: Boolean) : SessionWeather
}

/**
 * One session analysed for Session detail (SPEC Screens › Session detail). [channels] has what the lineup knows
 * of each guide number; a channel since dropped from the lineup is missing.
 */
data class SessionDetail(
    val info: SessionInfo,
    val deviceName: String?,
    val data: SessionData,
    val stats: SessionStats,
    val findings: List<Finding>,
    val channels: Map<String, Channel>,
    val weather: SessionWeather,
    /** The readings were deleted by the retention setting; the summary and events are what's left. */
    val readingsDeleted: Boolean = false,
)

/**
 * Saved sessions for History and Session detail: summaries, analysis, weather and deletion. Analysis runs on the
 * stored readings each time a session is opened, so findings follow the current thresholds and rules.
 */
@Singleton
class SessionRepository @Inject constructor(
    private val sessions: SessionDao,
    private val devices: DeviceRepository,
    private val settings: SettingsRepository,
    private val weatherSource: WeatherSource,
    private val clock: Clock,
    demoDevice: Optional<DemoDevice>,
) {
    private val demo: DemoDevice? = demoDevice.orElse(null)
    private val weatherLock = Mutex()

    /** Finished sessions of every kind, newest first. */
    val history: Flow<List<HistoryRow>> = sessions.observeHistory().map { rows ->
        rows.map { HistoryRow(it.session.info(), it.summary?.toSummary()) }
    }

    /** Summarises every finished session that has no summary yet: ones ended by a killed process, or older ones. */
    suspend fun summariseMissing() {
        for (s in sessions.unsummarised()) summarise(s)
    }

    suspend fun summarise(id: Long) {
        sessions.get(id)?.takeIf { it.endedAt != null }?.let { summarise(it) }
    }

    private suspend fun summarise(s: SessionEntity) {
        val thresholds = settings.settings.first().thresholds
        val readings = sessions.readings(s.id).map { it.logged() }
        val data = SessionData(s.startedAt, s.endedAt ?: s.startedAt, s.pollIntervalMs, readings, events(s.id))
        val stats = SessionStats.of(data, thresholds)
        sessions.upsertSummary(
            SessionSummaryEntity(
                sessionId = s.id,
                readings = stats.readings,
                goodShare = stats.goodShare,
                dips = stats.dips,
                medianSnq = stats.medianSnq,
                health = stats.health?.name,
                sparkline = sparklineOf(readings, data.startMs, data.endMs).joinToString(",") { it?.toString().orEmpty() },
            ),
        )
    }

    /** Loads and analyses one session; null when it's gone or still running. */
    suspend fun load(id: Long, zone: ZoneId): SessionDetail? {
        val s = sessions.get(id) ?: return null
        val end = s.endedAt ?: return null
        val thresholds: Thresholds = settings.settings.first().thresholds
        val channels = devices.channelList(s.deviceId).associateBy { it.guideNumber }
        val weather = weatherOf(s)
        val data = SessionData(
            startMs = s.startedAt,
            endMs = end,
            pollIntervalMs = s.pollIntervalMs,
            readings = sessions.readings(id).map { it.logged() },
            events = events(id),
            weather = (weather as? SessionWeather.Fetched)?.hours.orEmpty(),
            channels = channels.filterKeys { it in s.channels }.mapValues { (_, c) -> ChannelInfo(c.rfChannel, c.band) },
            zone = zone,
        )
        return SessionDetail(
            info = s.info(),
            deviceName = devices.device(s.deviceId)?.friendlyName,
            data = data,
            stats = SessionStats.of(data, thresholds),
            findings = Diagnosis.run(data, thresholds),
            channels = channels.filterKeys { it in s.channels },
            weather = weather,
            readingsDeleted = data.readings.isEmpty() && (sessions.summary(id)?.readings ?: 0) > 0,
        )
    }

    /**
     * Deletes readings older than the retention setting (SPEC "Storage"), run at app start after every finished
     * session has its summary. Returns how many readings went.
     */
    suspend fun deleteOldReadings(): Int {
        val days = settings.settings.first().retentionDays ?: return 0
        return sessions.deleteReadingsEndedBefore(clock.millis() - days * DAY_MS)
    }

    private suspend fun weatherOf(s: SessionEntity): SessionWeather = when {
        s.weatherFetched -> SessionWeather.Fetched(
            sessions.weather(s.id).map { WeatherHour(it.hourTs, it.tempC, it.windKph, it.gustKph, it.precipMm) },
            demo = s.deviceId == demo?.device?.deviceId,
        )
        !s.fetchWeather -> SessionWeather.NotAsked
        s.deviceId != demo?.device?.deviceId && settings.settings.first().home == null -> SessionWeather.NoHome
        else -> SessionWeather.Missing
    }

    /**
     * Fetches a finished session's weather if it was asked for and isn't stored yet: from Open-Meteo for home, or
     * made up for a Demo session. True when the session has its weather afterwards.
     */
    suspend fun fetchWeather(id: Long): Boolean = weatherLock.withLock {
        val s = sessions.get(id) ?: return false
        if (s.weatherFetched) return true
        val end = s.endedAt ?: return false
        if (!s.fetchWeather) return false
        val first = Math.floorDiv(s.startedAt, WeatherHour.HOUR_MS) * WeatherHour.HOUR_MS
        val hours = if (s.deviceId == demo?.device?.deviceId) {
            demo.weather(s.startedAt, end)
        } else {
            val home = settings.settings.first().home ?: return false
            weatherSource.hours(home.latitude, home.longitude, s.startedAt, end, clock.millis()) ?: return false
        }
        val kept = hours.filter { it.hourMs in first..end }
        sessions.saveWeather(id, kept.map { WeatherHourEntity(id, it.hourMs, it.tempC, it.windKph, it.gustKph, it.precipMm) })
        true
    }

    /** Finished sessions that asked for weather and don't have it, for a retry when the app starts. */
    suspend fun fetchMissingWeather() {
        for (row in sessions.observeHistory().first()) {
            val s = row.session
            if (s.fetchWeather && !s.weatherFetched) fetchWeather(s.id)
        }
    }

    suspend fun delete(ids: Collection<Long>) = sessions.deleteAll(ids.toList())

    private suspend fun events(id: Long): List<SignalEvent> = sessions.events(id).map {
        SignalEvent(it.kind, it.guideNumber, it.startTs, it.endTs, it.minSnq, it.minSeq, it.noLockMs)
    }

    private fun ReadingEntity.logged() =
        LoggedReading(
            ts, guideNumber, tuner, SignalSample(lock != LOCK_NONE, ss, snq, seq), atsc3 = lock == LOCK_ATSC3,
            counters = if (transportErrors == null && crcErrors == null && resyncs == null && networkErrors == null) null
            else ErrorCounters(transportErrors, crcErrors, resyncs, networkErrors),
        )

    private fun SessionEntity.info() = SessionInfo(id, deviceId, kind, startedAt, endedAt, channels, tuner, pollIntervalMs, note, firmware, watched, holder)

    private fun SessionSummaryEntity.toSummary() = SessionSummary(
        readings = readings,
        goodShare = goodShare,
        dips = dips,
        medianSnq = medianSnq,
        health = health?.let { h -> Health.entries.firstOrNull { it.name == h } },
        sparkline = if (sparkline.isEmpty()) emptyList() else sparkline.split(',').map { it.toIntOrNull() },
    )

    private companion object {
        const val LOCK_NONE = "none"
        const val LOCK_ATSC3 = "atsc3"
        const val DAY_MS = 86_400_000L
    }
}
