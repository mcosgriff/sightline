package io.cosgriff.sightline.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Devices and channels (v1); sessions and readings (v2, for sweeps); events, weather hours and a session's
 * fetch-weather choice (v3, for Monitor); session summaries (v4, for History).
 */
@Database(
    entities = [
        DeviceEntity::class, ChannelEntity::class, SessionEntity::class, ReadingEntity::class,
        EventEntity::class, WeatherHourEntity::class, SessionSummaryEntity::class,
    ],
    version = 4,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4)],
)
@TypeConverters(Converters::class)
abstract class SightlineDatabase : RoomDatabase() {
    abstract fun devices(): DeviceDao
    abstract fun channels(): ChannelDao
    abstract fun sessions(): SessionDao
}
