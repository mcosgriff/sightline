package io.cosgriff.sightline.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import io.cosgriff.sightline.analysis.EventKind

/** An HDHomeRun Sightline has seen (SPEC "Data model"). Refreshed on each discovery. */
@Entity(tableName = "device")
data class DeviceEntity(
    @PrimaryKey val deviceId: String,
    val friendlyName: String,
    val model: String,
    val firmware: String,
    val baseUrl: String,
    val lineupUrl: String?,
    val tunerCount: Int,
    /** The control protocol address, an IP. */
    val address: String,
    val controlPort: Int,
    /** UTC epoch ms of the last time it answered. */
    val lastSeen: Long,
    /** What the user typed to add it, when added by address rather than found by discovery. */
    val addedAs: String?,
)

/** One lineup entry. [rfChannel], [frequencyHz] and [band] are filled the first time it is tuned. */
@Entity(tableName = "channel", primaryKeys = ["deviceId", "guideNumber"])
data class ChannelEntity(
    val deviceId: String,
    val guideNumber: String,
    val guideName: String,
    /** The lineup entry's URL, `http://<ip>:5004/auto/v<guide>`; the stream for ATSC 3.0 (ADR-0004). */
    val streamUrl: String,
    /** Position in the device's lineup, for stepping through channels in its order. */
    val position: Int,
    val rfChannel: Int? = null,
    val frequencyHz: Long? = null,
    /** `Band` name from `:protocol`. */
    val band: String? = null,
    /** True once seen locked as ATSC 3.0. */
    val atsc3: Boolean = false,
    val favorite: Boolean = false,
)

/** What a session was (SPEC "Data model"). Stored by name. */
enum class SessionKind { LIVE, AIM, MONITOR, SWEEP }

/**
 * One run (SPEC "Data model"). [channels] are the guide numbers it covered, in order; for a sweep, every channel
 * it tried, including those it couldn't tune (they have no readings). [dwellMs] is a sweep's time per channel.
 */
@Entity(tableName = "session", indices = [Index("deviceId", "kind", "startedAt")])
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: String,
    val kind: SessionKind,
    val startedAt: Long,
    val endedAt: Long?,
    val channels: List<String>,
    /** The tuner, when the whole session used one. A sweep may move between tuners; each reading says which. */
    val tuner: Int?,
    val pollIntervalMs: Long,
    val dwellMs: Long? = null,
    val note: String? = null,
    val weatherFetched: Boolean = false,
    /** A monitor session asked for weather afterwards (SPEC Screens › Monitor setup). */
    @ColumnInfo(defaultValue = "0") val fetchWeather: Boolean = false,
    /** The device's firmware version when the session started; null for sessions from before it was kept. */
    val firmware: String? = null,
    /**
     * A monitor that read a tuner another client was using, without holding it (GLOSSARY "Watch a tuner in use").
     * [channels] then fill in as that client tunes them, and [holder] is the client's address as last seen.
     */
    @ColumnInfo(defaultValue = "0") val watched: Boolean = false,
    val holder: String? = null,
)

/** One poll (GLOSSARY "Reading"). [rawStatus] is kept only when the line couldn't be parsed. */
@Entity(
    tableName = "reading",
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId", "ts")],
)
data class ReadingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    /** UTC epoch ms. */
    val ts: Long,
    val guideNumber: String,
    val tuner: Int,
    val ss: Int,
    val snq: Int,
    val seq: Int,
    val bps: Long?,
    val pps: Long?,
    /** The `lock=` value as sent (`8vsb`, `atsc3`, `none`), so ATSC 3.0 readings can be told apart. */
    val lock: String,
    val headingDeg: Double? = null,
    val rawStatus: String? = null,
    /**
     * `/tunerN/debug`'s counters read with this reading, each a running count since the tuner's last tune
     * (SPEC "Packet errors"): `ts te`, `ts crc`, `dev resync`, `net err`. Null when not read.
     */
    val transportErrors: Long? = null,
    val crcErrors: Long? = null,
    val resyncs: Long? = null,
    val networkErrors: Long? = null,
)

/**
 * What the dip detector recorded (SPEC "Data model", `:analysis` `SignalEvent`). [guideNumber] is null for
 * device-wide events; [minSnq] and [minSeq] are null when the tuner never locked during it; [noLockMs] is how much
 * of it had no lock.
 */
@Entity(
    tableName = "event",
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId", "startTs")],
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val startTs: Long,
    val endTs: Long,
    val guideNumber: String?,
    val kind: EventKind,
    val minSnq: Int?,
    val minSeq: Int?,
    val noLockMs: Long,
)

/** One hour of Open-Meteo history for a session (SPEC "Data model"), metric. Fetched after the session (milestone 5). */
@Entity(
    tableName = "weather_hour",
    primaryKeys = ["sessionId", "hourTs"],
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
)
data class WeatherHourEntity(
    val sessionId: Long,
    /** UTC epoch ms at the start of the hour. */
    val hourTs: Long,
    val tempC: Double?,
    val windKph: Double?,
    val gustKph: Double?,
    val precipMm: Double?,
)

/**
 * What History shows of a session, computed once it ends (SPEC Screens › History) and kept when old readings are
 * deleted (SPEC "Storage"). [goodShare] is 0..1, null with no readings; [health] a `Health` name; [sparkline] the
 * lowest snq of each of a few stretches, comma-separated, empty where nothing was read.
 */
@Entity(
    tableName = "session_summary",
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
)
data class SessionSummaryEntity(
    @PrimaryKey val sessionId: Long,
    val readings: Int,
    val goodShare: Double?,
    val dips: Int,
    val medianSnq: Int?,
    val health: String?,
    val sparkline: String,
)

/** A session with its summary, if made yet. */
data class SessionWithSummary(
    @androidx.room.Embedded val session: SessionEntity,
    @androidx.room.Relation(parentColumn = "id", entityColumn = "sessionId") val summary: SessionSummaryEntity?,
)

class Converters {
    @TypeConverter
    fun fromList(value: List<String>): String = value.joinToString(SEPARATOR)

    @TypeConverter
    fun toList(value: String): List<String> = if (value.isEmpty()) emptyList() else value.split(SEPARATOR)

    private companion object {
        /** Guide numbers are digits and dots, so a comma never appears in one. */
        const val SEPARATOR = ","
    }
}
