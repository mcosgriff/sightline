package io.cosgriff.sightline.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {
    @Query("SELECT * FROM device ORDER BY friendlyName, deviceId")
    fun observeAll(): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM device WHERE deviceId = :deviceId")
    suspend fun get(deviceId: String): DeviceEntity?

    @Upsert
    suspend fun upsert(device: DeviceEntity)

    @Query("DELETE FROM device WHERE deviceId = :deviceId")
    suspend fun delete(deviceId: String)
}

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channel WHERE deviceId = :deviceId ORDER BY position")
    fun observe(deviceId: String): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channel WHERE deviceId = :deviceId ORDER BY position")
    suspend fun list(deviceId: String): List<ChannelEntity>

    @Query("SELECT * FROM channel WHERE deviceId = :deviceId AND guideNumber = :guideNumber")
    suspend fun get(deviceId: String, guideNumber: String): ChannelEntity?

    @Upsert
    suspend fun upsertAll(channels: List<ChannelEntity>)

    @Query("DELETE FROM channel WHERE deviceId = :deviceId AND guideNumber NOT IN (:keep)")
    suspend fun deleteOthers(deviceId: String, keep: List<String>)

    /** Replaces the lineup, keeping what was learned by tuning (RF channel, band, ATSC 3.0) and favourites. */
    @Transaction
    suspend fun replaceLineup(deviceId: String, lineup: List<ChannelEntity>) {
        val known = list(deviceId).associateBy { it.guideNumber }
        upsertAll(
            lineup.map { new ->
                known[new.guideNumber]?.let {
                    new.copy(rfChannel = it.rfChannel, frequencyHz = it.frequencyHz, band = it.band, atsc3 = it.atsc3, favorite = it.favorite)
                } ?: new
            },
        )
        deleteOthers(deviceId, lineup.map { it.guideNumber })
    }

    @Query(
        "UPDATE channel SET rfChannel = :rf, frequencyHz = :hz, band = :band, atsc3 = :atsc3 " +
            "WHERE deviceId = :deviceId AND guideNumber = :guideNumber",
    )
    suspend fun recordTuned(deviceId: String, guideNumber: String, rf: Int?, hz: Long?, band: String?, atsc3: Boolean)
}

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Insert
    suspend fun insertReadings(readings: List<ReadingEntity>)

    /** A finished session and its readings, written together so a half-saved sweep never shows. */
    @Transaction
    suspend fun insertWithReadings(session: SessionEntity, readings: List<ReadingEntity>): Long {
        val id = insert(session)
        insertReadings(readings.map { it.copy(sessionId = id) })
        return id
    }

    @Query("SELECT * FROM session WHERE id = :id")
    suspend fun get(id: Long): SessionEntity?

    @Query("SELECT * FROM session WHERE deviceId = :deviceId AND kind = :kind ORDER BY startedAt DESC")
    fun observe(deviceId: String, kind: SessionKind): Flow<List<SessionEntity>>

    @Query("SELECT * FROM reading WHERE sessionId = :sessionId ORDER BY ts, id")
    suspend fun readings(sessionId: Long): List<ReadingEntity>

    @Query("DELETE FROM session WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert
    suspend fun insertEvents(events: List<EventEntity>)

    /** A monitor's batch of readings and the events finished with them, in one transaction (SPEC "Polling loop"). */
    @Transaction
    suspend fun append(readings: List<ReadingEntity>, events: List<EventEntity>) {
        if (readings.isNotEmpty()) insertReadings(readings)
        if (events.isNotEmpty()) insertEvents(events)
    }

    @Query("UPDATE session SET endedAt = :endedAt, tuner = :tuner WHERE id = :id")
    suspend fun finish(id: Long, endedAt: Long, tuner: Int?)

    /** Sessions of [kind] never ended: their process was killed. */
    @Query("SELECT * FROM session WHERE kind = :kind AND endedAt IS NULL")
    suspend fun unfinished(kind: SessionKind): List<SessionEntity>

    @Query("SELECT MAX(ts) FROM reading WHERE sessionId = :sessionId")
    suspend fun lastReadingTs(sessionId: Long): Long?

    @Query("SELECT * FROM event WHERE sessionId = :sessionId ORDER BY startTs, id")
    suspend fun events(sessionId: Long): List<EventEntity>
}
