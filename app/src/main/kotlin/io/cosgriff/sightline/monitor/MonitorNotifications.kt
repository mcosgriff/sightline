package io.cosgriff.sightline.monitor

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.cosgriff.sightline.MainActivity
import io.cosgriff.sightline.R
import io.cosgriff.sightline.analysis.DipAlert
import io.cosgriff.sightline.analysis.dipCount
import io.cosgriff.sightline.data.monitor.MonitorPhase
import io.cosgriff.sightline.data.monitor.MonitorStatus
import io.cosgriff.sightline.protocol.LostReason
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monitor's notifications (SPEC Screens › Monitor running): the ongoing one with the current snq, the dip count and
 * a Stop action; and, on their own channel, dip summaries (at most one per 10 minutes, if asked for) and a note
 * when the monitor pauses because its tuner was taken.
 */
@Singleton
class MonitorNotifications @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_ONGOING, context.getString(R.string.monitor_channel_ongoing), NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.monitor_channel_alerts), NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
    }

    /** The ongoing notification; [status] null while the session is starting. */
    fun ongoing(status: MonitorStatus?): Notification {
        val res = context.resources
        val title = when {
            status == null -> context.getString(R.string.monitor_notification_starting)
            else -> monitorTitle(res, status)
        }
        val text = status?.let {
            val dips = it.allEvents.dipCount()
            when (it.phase) {
                is MonitorPhase.Watching, is MonitorPhase.Observing -> it.latest?.takeIf { s -> s.isLocked }
                    ?.let { s -> res.getQuantityString(R.plurals.monitor_notification_reading, dips, s.snq, dips) }
                    ?: res.getQuantityString(R.plurals.monitor_notification_no_lock, dips, dips)
                MonitorPhase.Claiming -> context.getString(R.string.monitor_phase_claiming)
                is MonitorPhase.AllBusy -> context.getString(R.string.monitor_notification_all_busy)
                is MonitorPhase.Paused -> context.getString(R.string.monitor_notification_paused)
                is MonitorPhase.Unreachable -> context.getString(R.string.monitor_notification_unreachable)
                is MonitorPhase.ObservingIdle -> context.getString(R.string.monitor_notification_watch_idle)
            }
        }
        val stop = PendingIntent.getService(
            context, REQUEST_STOP,
            Intent(context, MonitorService::class.java).setAction(MonitorService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_monitor)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, context.getString(R.string.monitor_stop), stop)
            .build()
    }

    fun update(status: MonitorStatus?) = post(ONGOING_ID, ongoing(status))

    /** "3 dips on 9.1 in the last hour". */
    fun dipAlert(alert: DipAlert) {
        val res = context.resources
        val text = when (alert.channels.size) {
            0, 1 -> res.getQuantityString(R.plurals.monitor_alert_one_channel, alert.dips, alert.dips, alert.channels.firstOrNull() ?: "")
            else -> res.getQuantityString(R.plurals.monitor_alert_channels, alert.dips, alert.dips, alert.channels.size)
        }
        post(ALERT_ID, alert(context.getString(R.string.monitor_alert_title), text))
    }

    /** The tuner was taken and no other was free: the monitor waits for one (SPEC "Tuner lost": pause and notify). */
    fun paused(reason: LostReason) {
        val holder = (reason as? LostReason.TakenBy)?.holder
        val text = holder?.let { context.getString(R.string.monitor_paused_taken_by, it) } ?: context.getString(R.string.monitor_paused_taken)
        post(PAUSED_ID, alert(context.getString(R.string.monitor_paused_title), text))
    }

    private fun alert(title: String, text: String) = NotificationCompat.Builder(context, CHANNEL_ALERTS)
        .setSmallIcon(R.drawable.ic_stat_monitor)
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setContentIntent(openApp())
        .setAutoCancel(true)
        .build()

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, REQUEST_OPEN,
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_MONITOR, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Posts unless the user turned notifications off; the service's own one shows in the task manager regardless. */
    private fun post(id: Int, notification: Notification) {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) manager.notify(id, notification)
    }

    companion object {
        const val ONGOING_ID = 1
        private const val ALERT_ID = 2
        private const val PAUSED_ID = 3
        private const val CHANNEL_ONGOING = "monitor"
        private const val CHANNEL_ALERTS = "monitor_alerts"
        private const val REQUEST_STOP = 1
        private const val REQUEST_OPEN = 2
    }
}
