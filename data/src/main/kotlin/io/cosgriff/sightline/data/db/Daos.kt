package io.cosgriff.sightline.data.db

import androidx.room.Dao
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
