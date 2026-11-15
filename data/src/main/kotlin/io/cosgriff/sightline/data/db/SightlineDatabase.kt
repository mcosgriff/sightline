package io.cosgriff.sightline.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Devices and channels (v1); sessions and readings (v2, for sweeps); events, weather hours and a session's
 * fetch-weather choice (v3, for Monitor); session summaries (v4, for History); packet error counters and a
 * session's firmware (v5); watched sessions (v6); a watch of every tuner, with readings and events keyed by device
 * and tuner (v7); the phone's Wi-Fi with each monitor session (v8).
 */
@Database(
    entities = [
        DeviceEntity::class, ChannelEntity::class, SessionEntity::class, ReadingEntity::class,
        EventEntity::class, WeatherHourEntity::class, SessionSummaryEntity::class, WifiSampleEntity::class,
    ],
    version = 8,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7), AutoMigration(from = 7, to = 8)],
)
@TypeConverters(Converters::class)
abstract class SightlineDatabase : RoomDatabase() {
    abstract fun devices(): DeviceDao
    abstract fun channels(): ChannelDao
    abstract fun sessions(): SessionDao
}
