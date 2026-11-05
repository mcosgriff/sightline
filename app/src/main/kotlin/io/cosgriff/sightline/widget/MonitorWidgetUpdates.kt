package io.cosgriff.sightline.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import dagger.hilt.android.qualifiers.ApplicationContext
import io.cosgriff.sightline.data.di.ApplicationScope
import io.cosgriff.sightline.data.monitor.MonitorStatus
import io.cosgriff.sightline.monitor.MonitorController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.TimeSource

/**
 * Redraws the widget when the monitor's state changes in a way it shows (started, ended, a new event, a new
 * phase), and at most every [REFRESH_MS] otherwise, so the minutes it shows move. While the widget is being drawn,
 * Glance follows the status itself.
 */
@Singleton
class MonitorWidgetUpdates @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val controller: MonitorController,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            var last: MonitorStatus? = null
            var first = true
            var shownAt = TimeSource.Monotonic.markNow()
            controller.status.collect { s ->
                val changed = first || (s == null) != (last == null) || s?.ended != last?.ended ||
                    s?.allEvents?.size != last?.allEvents?.size || s?.phase?.let { it::class } != last?.phase?.let { it::class }
                if (changed || shownAt.elapsedNow().inWholeMilliseconds >= REFRESH_MS) {
                    runCatching { MonitorWidget().updateAll(context) }
                    shownAt = TimeSource.Monotonic.markNow()
                }
                first = false
                last = s
            }
        }
    }

    private companion object {
        const val REFRESH_MS = 60_000L
    }
}
