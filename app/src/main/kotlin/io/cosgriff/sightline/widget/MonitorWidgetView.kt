package io.cosgriff.sightline.widget

import android.content.Context
import io.cosgriff.sightline.R
import io.cosgriff.sightline.analysis.Health
import io.cosgriff.sightline.analysis.SignalSample
import io.cosgriff.sightline.analysis.Thresholds
import io.cosgriff.sightline.analysis.assess
import io.cosgriff.sightline.analysis.dipCount
import io.cosgriff.sightline.data.monitor.MonitorPhase
import io.cosgriff.sightline.data.monitor.MonitorStatus
import io.cosgriff.sightline.ui.components.label

/** What the Monitor widget shows, worded. */
sealed interface MonitorWidgetView {
    /** No session: a line saying so, and a tap opens Monitor to set one up. */
    data class Idle(val title: String, val body: String) : MonitorWidgetView

    /**
     * A session running: what it watches, the latest quality with its [health] in a word (never colour alone),
     * and the dips so far with the time it has run. [quality] is null while there's no locked reading.
     */
    data class Running(val title: String, val quality: String?, val health: Health?, val healthWord: String?, val detail: String) : MonitorWidgetView

    /** The session just ended, until the Monitor screen moves on. */
    data class Ended(val title: String, val body: String) : MonitorWidgetView
}

/** Words [status] for the widget (SPEC Screens › 7: "the same" as the notification). */
fun monitorWidgetView(context: Context, status: MonitorStatus?, thresholds: Thresholds, nowMs: Long): MonitorWidgetView {
    val res = context.resources
    if (status == null) return MonitorWidgetView.Idle(context.getString(R.string.widget_idle_title), context.getString(R.string.widget_idle_body))
    val dips = status.allEvents.dipCount()
    val dipsText = res.getQuantityString(R.plurals.widget_dips, dips, dips)
    if (status.ended != null) {
        return MonitorWidgetView.Ended(context.getString(R.string.widget_ended_title), dipsText)
    }
    val title = if (status.plan.rotate) {
        res.getQuantityString(R.plurals.monitor_title_rotate, status.plan.channels.size, status.plan.channels.size)
    } else {
        context.getString(R.string.monitor_title_hold, status.channel.guideNumber)
    }
    val minutes = ((nowMs - status.startedAt).coerceAtLeast(0) / 60_000).toInt()
    val elapsed = if (minutes < 60) context.getString(R.string.widget_minutes, minutes) else context.getString(R.string.widget_hours_minutes, minutes / 60, minutes % 60)
    val detail = context.getString(R.string.widget_detail, dipsText, elapsed)
    val latest = status.latest
    return when (val phase = status.phase) {
        is MonitorPhase.Watching -> {
            val health = latest?.let { thresholds.assess(SignalSample(it.isLocked, it.ss, it.snq, it.seq)).health }
            MonitorWidgetView.Running(
                title = title,
                quality = latest?.takeIf { it.isLocked }?.snq?.toString(),
                health = health,
                healthWord = health?.let { context.getString(it.label) },
                detail = detail,
            )
        }
        else -> MonitorWidgetView.Running(
            title = title,
            quality = null,
            health = null,
            healthWord = context.getString(
                when (phase) {
                    MonitorPhase.Claiming -> R.string.monitor_phase_claiming
                    is MonitorPhase.AllBusy -> R.string.monitor_notification_all_busy
                    is MonitorPhase.Paused -> R.string.monitor_notification_paused
                    else -> R.string.monitor_notification_unreachable
                },
            ),
            detail = detail,
        )
    }
}
