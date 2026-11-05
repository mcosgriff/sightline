package io.cosgriff.sightline

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.cosgriff.sightline.data.device.StaleLockRecovery
import io.cosgriff.sightline.data.di.ApplicationScope
import io.cosgriff.sightline.data.monitor.MonitorRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class SightlineApp : Application() {
    @Inject lateinit var staleLocks: StaleLockRecovery

    @Inject lateinit var monitorSessions: MonitorRepository

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        // Tuners a killed process left claimed are given back first (SPEC "Polling loop").
        staleLocks.start()
        // A monitor session the killed process was running is ended at its last reading.
        scope.launch { monitorSessions.closeAbandoned() }
    }
}
