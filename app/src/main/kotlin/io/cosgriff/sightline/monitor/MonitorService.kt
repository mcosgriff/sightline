package io.cosgriff.sightline.monitor

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import io.cosgriff.sightline.data.monitor.MonitorPhase
import io.cosgriff.sightline.data.monitor.MonitorStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.TimeSource

/**
 * Runs the monitor session (SPEC "Monitor mode › Service"): a foreground service of type `connectedDevice`, which
 * has no time cap, holding a partial wake lock and a Wi-Fi lock while it runs, so polling carries on with the
 * screen off. Stop (from the screen or the notification) cancels the run, which writes what it has and releases the
 * tuner before the service goes. Not sticky: if the system kills it, the session is closed at the next app start.
 */
@AndroidEntryPoint
class MonitorService : Service() {
    @Inject lateinit var controller: MonitorController

    @Inject lateinit var notifications: MonitorNotifications

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var run: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch {
                run?.cancelAndJoin()
                finish()
            }
            return START_NOT_STICKY
        }
        notifications.createChannels()
        ServiceCompat.startForeground(
            this, MonitorNotifications.ONGOING_ID, notifications.ongoing(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        if (run?.isActive == true) return START_NOT_STICKY
        run = scope.launch {
            val updates = launch { keepNotificationCurrent() }
            controller.runPending(onAlert = notifications::dipAlert)
            updates.cancel()
            finish()
        }
        return START_NOT_STICKY
    }

    /** Refreshes the notification when something worth showing changes, and at most every [REFRESH_MS] otherwise. */
    private suspend fun keepNotificationCurrent() {
        var last: MonitorStatus? = null
        var shownAt = TimeSource.Monotonic.markNow()
        controller.status.filterNotNull().collect { s ->
            if (wakeLock == null) acquireLocks(s)
            val phase = s.phase
            if (phase is MonitorPhase.Paused && last?.phase !is MonitorPhase.Paused) notifications.paused(phase.reason)
            val changed = last == null || s.phase::class != last?.phase?.let { it::class } || s.allEvents.size != last?.allEvents?.size
            if (changed || shownAt.elapsedNow().inWholeMilliseconds >= REFRESH_MS) {
                notifications.update(s)
                shownAt = TimeSource.Monotonic.markNow()
            }
            last = s
        }
    }

    private fun acquireLocks(status: MonitorStatus) {
        // Held for the planned length plus a margin, or a long while when open-ended; released at the end either way.
        val timeout = (status.plan.durationMs ?: OPEN_ENDED_MS) + LOCK_MARGIN_MS
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sightline:monitor")
            .apply {
                setReferenceCounted(false)
                acquire(timeout)
            }
        val wifi = getSystemService(WifiManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            // Deprecated from API 34, where low latency takes its place; before that, the mode that keeps Wi-Fi awake.
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wifi.createWifiLock(mode, "sightline:monitor").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock = null
    }

    private fun finish() {
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // Killed or stopped another way: the run's own clean-up still writes and releases the tuner.
        run?.cancel()
        releaseLocks()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "io.cosgriff.sightline.monitor.STOP"
        private const val REFRESH_MS = 5_000L
        private const val OPEN_ENDED_MS = 7 * 24 * 3_600_000L
        private const val LOCK_MARGIN_MS = 10 * 60_000L
    }
}
