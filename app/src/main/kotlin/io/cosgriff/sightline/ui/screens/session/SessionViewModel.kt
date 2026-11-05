package io.cosgriff.sightline.ui.screens.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import io.cosgriff.sightline.analysis.TimelineSeries
import io.cosgriff.sightline.analysis.timelineOf
import io.cosgriff.sightline.data.session.SessionDetail
import io.cosgriff.sightline.data.session.SessionRepository
import io.cosgriff.sightline.data.settings.Settings
import io.cosgriff.sightline.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
) {
    val zoomed: Boolean get() = detail != null && window != null && (window.startMs > detail.data.startMs || window.endMs < detail.data.endMs)
}

/** Session detail: loads and analyses one saved session, and zooms its timeline. */
@HiltViewModel(assistedFactory = SessionViewModel.Factory::class)
class SessionViewModel @AssistedInject constructor(
    @Assisted private val sessionId: Long,
    private val sessions: SessionRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(sessionId: Long): SessionViewModel
    }

    private val _state = MutableStateFlow(SessionUiState())
    val state: StateFlow<SessionUiState> = _state.asStateFlow()

    val settings: StateFlow<Settings> = settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    private val zone: ZoneId = ZoneId.systemDefault()

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

    private fun lines(detail: SessionDetail, window: TimeWindow): List<TimelineSeries> = detail.data.guideNumbers.map { g ->
        timelineOf(detail.data.byChannel.getValue(g), window.startMs, window.endMs, TIMELINE_BUCKETS)
    }

    private companion object {
        /** Enough for a phone's width at two or three pixels a point. */
        const val TIMELINE_BUCKETS = 360
    }
}
