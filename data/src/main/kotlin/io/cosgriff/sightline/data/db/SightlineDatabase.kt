package io.cosgriff.sightline.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/** Devices and channels now; sessions, readings, events and weather arrive with Monitor (milestone 4). */
@Database(entities = [DeviceEntity::class, ChannelEntity::class], version = 1, exportSchema = true)
abstract class SightlineDatabase : RoomDatabase() {
    abstract fun devices(): DeviceDao
    abstract fun channels(): ChannelDao
}
