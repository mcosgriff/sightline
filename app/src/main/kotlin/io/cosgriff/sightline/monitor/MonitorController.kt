package io.cosgriff.sightline.monitor

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.cosgriff.sightline.analysis.DipAlert
import io.cosgriff.sightline.data.device.DeviceRepository
import io.cosgriff.sightline.data.di.ApplicationScope
import io.cosgriff.sightline.data.session.SessionRepository
import io.cosgriff.sightline.data.device.TunerAccess
import io.cosgriff.sightline.data.device.TunerLeases
import io.cosgriff.sightline.data.monitor.MonitorEnd
import io.cosgriff.sightline.data.monitor.MonitorPlan
import io.cosgriff.sightline.data.monitor.MonitorRepository
import io.cosgriff.sightline.data.monitor.MonitorRunner
import io.cosgriff.sightline.data.monitor.MonitorStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one monitor session: the screen starts and stops it here, [MonitorService] runs it, and both watch
 * [status]. The run belongs to the service, so it lasts exactly as long as the foreground service does.
 */
@Singleton
class MonitorController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val access: TunerAccess,
    private val leases: TunerLeases,
    private val devices: DeviceRepository,
    private val repository: MonitorRepository,
    private val sessions: SessionRepository,
    private val clock: Clock,
    @param:ApplicationScope private val appScope: CoroutineScope,
) {
    private val _status = MutableStateFlow<MonitorStatus?>(null)

    /** The session running, or the one that just ended until [dismiss]; null before any. */
    val status: StateFlow<MonitorStatus?> = _status.asStateFlow()

    @Volatile
    private var pending: MonitorPlan? = null

    /** Starts the foreground service with [plan]. Ignored while a session runs. */
    fun start(plan: MonitorPlan) {
        val now = _status.value
        if (now != null && now.ended == null) return
        pending = plan
        ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
    }

    /** Ends the session; what was read so far is saved. */
    fun stop() {
        pending = null
        context.startService(Intent(context, MonitorService::class.java).setAction(MonitorService.ACTION_STOP))
    }

    /** Forgets a finished session, so the screen goes back to setup. */
    fun dismiss() {
        if (_status.value?.ended != null) _status.value = null
    }

    /** Runs the plan [start] left, to its end or until cancelled. Null when there was none. Called by the service. */
    internal suspend fun runPending(onAlert: (DipAlert) -> Unit): MonitorEnd? {
        val plan = pending ?: return null
        pending = null
        val runner = MonitorRunner(
            sessions = access.sessions(plan.device),
            leases = leases,
            sink = { channel, rf, hz, band, atsc3 -> devices.recordTuned(channel, rf, hz, band, atsc3) },
            log = repository,
            clock = clock,
            onAlert = onAlert,
        )
        return coroutineScope {
            val mirror = launch { runner.status.collect { if (it != null) _status.value = it } }
            try {
                runner.run(plan)
            } finally {
                mirror.cancel()
                runner.status.value?.let { ended ->
                    _status.value = ended
                    // After the session (SPEC): its History summary, then the weather it asked for. Outlives the service.
                    appScope.launch {
                        sessions.summarise(ended.sessionId)
                        sessions.fetchWeather(ended.sessionId)
                    }
                }
            }
        }
    }
}
