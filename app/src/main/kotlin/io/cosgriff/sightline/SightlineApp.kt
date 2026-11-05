package io.cosgriff.sightline

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.cosgriff.sightline.data.device.StaleLockRecovery
import javax.inject.Inject

@HiltAndroidApp
class SightlineApp : Application() {
    @Inject lateinit var staleLocks: StaleLockRecovery

    override fun onCreate() {
        super.onCreate()
        // Tuners a killed process left claimed are given back first (SPEC "Polling loop").
        staleLocks.start()
    }
}
