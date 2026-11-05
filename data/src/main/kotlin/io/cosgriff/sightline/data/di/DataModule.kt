package io.cosgriff.sightline.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import dagger.Binds
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.cosgriff.sightline.data.db.ChannelDao
import io.cosgriff.sightline.data.db.DeviceDao
import io.cosgriff.sightline.data.db.SessionDao
import io.cosgriff.sightline.data.db.SightlineDatabase
import io.cosgriff.sightline.data.device.DemoDevice
import io.cosgriff.sightline.data.heading.HeadingSource
import io.cosgriff.sightline.data.heading.RotationVectorHeading
import io.cosgriff.sightline.data.settings.SettingsStore
import io.cosgriff.sightline.data.settings.StateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    /** Bound only in debug builds (the Demo tuner); absent in release. */
    @BindsOptionalOf
    abstract fun demoDevice(): DemoDevice

    @Binds
    abstract fun headingSource(impl: RotationVectorHeading): HeadingSource

    companion object {
        /** The one clock. Readings are stamped from it in UTC epoch milliseconds, so tests can set the time. */
        @Provides
        @Singleton
        fun clock(): Clock = Clock.systemUTC()

        @Provides
        @Singleton
        @ApplicationScope
        fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        @Provides
        @Singleton
        fun database(@ApplicationContext context: Context): SightlineDatabase =
            Room.databaseBuilder(context, SightlineDatabase::class.java, "sightline.db").build()

        @Provides
        fun deviceDao(db: SightlineDatabase): DeviceDao = db.devices()

        @Provides
        fun channelDao(db: SightlineDatabase): ChannelDao = db.channels()

        @Provides
        fun sessionDao(db: SightlineDatabase): SessionDao = db.sessions()

        @Provides
        @Singleton
        @SettingsStore
        fun settingsStore(@ApplicationContext context: Context): DataStore<Preferences> =
            PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }

        @Provides
        @Singleton
        @StateStore
        fun stateStore(@ApplicationContext context: Context): DataStore<Preferences> =
            PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("state") }
    }
}
