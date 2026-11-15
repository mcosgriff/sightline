package io.cosgriff.sightline.monitor

import android.content.res.Resources
import io.cosgriff.sightline.R
import io.cosgriff.sightline.data.monitor.MonitorStatus

/** The running session's title, as Monitor, its notification and the widget show it. */
fun monitorTitle(res: Resources, status: MonitorStatus): String {
    val plan = status.plan
    return when {
        plan.compare -> res.getString(R.string.monitor_title_compare, status.channel.guideNumber)
        plan.wholeDevice -> if (plan.wholeDevices.size == 1) res.getString(R.string.monitor_title_watch_all, plan.device.friendlyName)
        else res.getQuantityString(R.plurals.monitor_title_watch_all_devices, plan.wholeDevices.size, plan.wholeDevices.size)
        plan.watch -> {
            val guide = status.channel.guideNumber
            if (guide.isEmpty()) res.getString(R.string.monitor_title_watch, plan.tuner ?: 0)
            else res.getString(R.string.monitor_title_watch_channel, plan.tuner ?: 0, guide)
        }
        plan.rotate -> res.getQuantityString(R.plurals.monitor_title_rotate, plan.channels.size, plan.channels.size)
        else -> res.getString(R.string.monitor_title_hold, status.channel.guideNumber)
    }
}
