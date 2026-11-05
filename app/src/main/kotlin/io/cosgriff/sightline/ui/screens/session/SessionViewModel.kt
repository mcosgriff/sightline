package io.cosgriff.sightline.ui.screens.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import io.cosgriff.sightline.analysis.TimelineSeries
import io.cosgriff.sightline.analysis.timelineOf
import io.cosgriff.sightline.data.export.SessionExport
import io.cosgriff.sightline.data.session.SessionDetail
import io.cosgriff.sightline.data.session.SessionRepository
import io.cosgriff.sightline.data.settings.Settings
import io.cosgriff.sightline.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId

/** A stretch of the session the timeline shows. */
data class TimeWindow(val startMs: Long, val endMs: Long)

/** Which weather the panel under the timeline shows. */
enum class WeatherMetric { GUSTS, TEMPERATURE, RAIN }

data class SessionUiState(
    val loading: Boolean = true,
    /** Null once loaded: the session is gone. */
    val detail: SessionDetail? = null,
    /** The timeline's window: the whole session, or zoomed to an event or an hour. */
    val window: TimeWindow? = null,
    /** One series per channel, in the order first read, for [window]. */
    val lines: List<TimelineSeries> = emptyList(),
    val weatherMetric: WeatherMetric = WeatherMetric.GUSTS,
    val fetchingWeather: Boolean = false,
    /** A CSV or the report is being written. */
    val exporting: Boolean = false,
) {
    val zoomed: Boolean get() = detail != null && window != null && (window.startMs > detail.data.startMs || window.endMs < detail.data.endMs)
}

/** Files ready for the share sheet, with their MIME type. */
data class ShareFiles(val files: List<File>, val mimeType: String)

/** Session detail: loads and analyses one saved session, and zooms its timeline. */
@HiltViewModel(assistedFactory = SessionViewModel.Factory::class)
class SessionViewModel @AssistedInject constructor(
    @Assisted private val sessionId: Long,
    private val sessions: SessionRepository,
    private val settingsRepository: SettingsRepository,
    private val export: SessionExport,
    private val reportWriter: SessionReportWriter,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(sessionId: Long): SessionViewModel
    }

    private val _state = MutableStateFlow(SessionUiState())
    val state: StateFlow<SessionUiState> = _state.asStateFlow()

    val settings: StateFlow<Settings> = settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    private val zone: ZoneId = ZoneId.systemDefault()

    private val _shares = Channel<ShareFiles>(Channel.BUFFERED)

    /** Files to hand to the share sheet, once each. */
    val shares: Flow<ShareFiles> = _shares.receiveAsFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val detail = withContext(Dispatchers.Default) { sessions.load(sessionId, zone) }
        val window = detail?.let { TimeWindow(it.data.startMs, it.data.endMs) }
        _state.update { it.copy(loading = false, detail = detail, window = window, lines = detail?.let { d -> lines(d, window!!) }.orEmpty()) }
    }

    /** Shows [startMs]..[endMs] (kept inside the session) on the timeline, read again at that detail. */
    fun zoom(startMs: Long, endMs: Long) {
        val detail = _state.value.detail ?: return
        val window = TimeWindow(startMs.coerceAtLeast(detail.data.startMs), endMs.coerceAtMost(detail.data.endMs))
        if (window.endMs <= window.startMs) return
        viewModelScope.launch {
            val lines = withContext(Dispatchers.Default) { lines(detail, window) }
            _state.update { it.copy(window = window, lines = lines) }
        }
    }

    fun zoomOut() {
        val d = _state.value.detail ?: return
        zoom(d.data.startMs, d.data.endMs)
    }

    fun showWeather(metric: WeatherMetric) = _state.update { it.copy(weatherMetric = metric) }

    /** Tries Open-Meteo again, then re-analyses with the weather. */
    fun retryWeather() {
        if (_state.value.fetchingWeather) return
        _state.update { it.copy(fetchingWeather = true) }
        viewModelScope.launch {
            sessions.fetchWeather(sessionId)
            val window = _state.value.window
            load()
            window?.let { zoom(it.startMs, it.endMs) }
            _state.update { it.copy(fetchingWeather = false) }
        }
    }

    /** Saves home from one location fix, then fetches the weather for it. */
    fun saveHome(latitude: Double, longitude: Double) {
        viewModelScope.launch {
            settingsRepository.setHome(latitude, longitude)
            retryWeather()
        }
    }

    /** Writes the readings and events as two CSV files and offers them to share together. */
    fun exportCsv() = exporting {
        val readings = export.readings(sessionId, zone)
        val events = export.events(sessionId, zone)
        listOfNotNull(readings, events).takeIf { it.isNotEmpty() }?.let { ShareFiles(it, CSV) }
    }

    /** Writes the one-page report with [text] (worded by the screen) and offers it to share. */
    fun shareReport(text: ReportText, footer: String) = exporting {
        val detail = _state.value.detail ?: return@exporting null
        val file = export.reportFile(sessionId, zone) ?: return@exporting null
        val d = detail.data
        val chart = ReportChart(d.startMs, d.endMs, lines(detail, TimeWindow(d.startMs, d.endMs), REPORT_BUCKETS), d.events, settings.value.thresholds.snqGood)
        withContext(Dispatchers.IO) { reportWriter.write(file, text, chart, footer) }
        ShareFiles(listOf(file), PDF)
    }

    private fun exporting(block: suspend () -> ShareFiles?) {
        if (_state.value.exporting) return
        _state.update { it.copy(exporting = true) }
        viewModelScope.launch {
            try {
                block()?.let { _shares.send(it) }
            } finally {
                _state.update { it.copy(exporting = false) }
            }
        }
    }

    private fun lines(detail: SessionDetail, window: TimeWindow, buckets: Int = TIMELINE_BUCKETS): List<TimelineSeries> = detail.data.guideNumbers.map { g ->
        timelineOf(detail.data.byChannel.getValue(g), window.startMs, window.endMs, buckets)
    }

    private companion object {
        /** Enough for a phone's width at two or three pixels a point. */
        const val TIMELINE_BUCKETS = 360

        /** The report's chart is about 530 points wide. */
        const val REPORT_BUCKETS = 260
        const val CSV = "text/csv"
        const val PDF = "application/pdf"
    }
}
