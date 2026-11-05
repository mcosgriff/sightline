package io.cosgriff.sightline

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.cosgriff.sightline.data.device.StaleLockRecovery
import io.cosgriff.sightline.data.di.ApplicationScope
import io.cosgriff.sightline.data.monitor.MonitorRepository
import io.cosgriff.sightline.data.session.SessionRepository
import io.cosgriff.sightline.widget.MonitorWidgetUpdates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class SightlineApp : Application() {
    @Inject lateinit var staleLocks: StaleLockRecovery

    @Inject lateinit var monitorSessions: MonitorRepository

    @Inject lateinit var sessions: SessionRepository

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    @Inject lateinit var widget: MonitorWidgetUpdates

    override fun onCreate() {
        super.onCreate()
        // Tuners a killed process left claimed are given back first (SPEC "Polling loop").
        staleLocks.start()
        widget.start()
        // A monitor session the killed process was running is ended at its last reading.
        // Then every finished session gets its History summary, readings past the retention setting go (the
        // summaries stay), and weather a session asked for is tried again.
        scope.launch {
            monitorSessions.closeAbandoned()
            sessions.summariseMissing()
            sessions.deleteOldReadings()
            sessions.fetchMissingWeather()
        }
    }
}
