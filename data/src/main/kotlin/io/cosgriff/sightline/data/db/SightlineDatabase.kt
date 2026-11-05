package io.cosgriff.sightline.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Devices and channels (v1); sessions and readings (v2, for sweeps). Events and weather arrive with Monitor and
 * Session detail (milestones 4 and 5).
 */
@Database(
    entities = [DeviceEntity::class, ChannelEntity::class, SessionEntity::class, ReadingEntity::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
@TypeConverters(Converters::class)
abstract class SightlineDatabase : RoomDatabase() {
    abstract fun devices(): DeviceDao
    abstract fun channels(): ChannelDao
    abstract fun sessions(): SessionDao
}
