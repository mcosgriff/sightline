package io.cosgriff.sightline.ui.screens.aim

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.cosgriff.sightline.analysis.AimEvent
import io.cosgriff.sightline.analysis.AimTrace
import io.cosgriff.sightline.analysis.SignalSample
import io.cosgriff.sightline.analysis.Thresholds
import io.cosgriff.sightline.data.device.Channel
import io.cosgriff.sightline.data.device.Device
import io.cosgriff.sightline.data.device.DeviceRepository
import io.cosgriff.sightline.data.device.TunerAccess
import io.cosgriff.sightline.data.device.TunerLeases
import io.cosgriff.sightline.data.heading.CompassHeading
import io.cosgriff.sightline.data.heading.HeadingSource
import io.cosgriff.sightline.data.live.LiveSession
import io.cosgriff.sightline.data.live.LiveState
import io.cosgriff.sightline.data.settings.AppStateRepository
import io.cosgriff.sightline.data.settings.Settings
import io.cosgriff.sightline.data.settings.SettingsRepository
import io.cosgriff.sightline.protocol.TunerStatus
import io.cosgriff.sightline.ui.screens.Selection
import io.cosgriff.sightline.ui.screens.loadSelection
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

/** The second tuner's channel and what it reads (SPEC Aim: "a small second reading"). */
data class SecondTuner(val channel: Channel, val live: LiveState? = null)

data class AimUiState(
    /** Null while loading. */
    val selection: Selection? = null,
    val live: LiveState? = null,
    val trace: AimTrace = AimTrace(),
    val heading: CompassHeading? = null,
    /** False on a phone without a rotation vector sensor. */
    val compass: Boolean = true,
    val second: SecondTuner? = null,
    val thresholds: Thresholds = Thresholds.DEFAULT,
) {
    val status: TunerStatus? get() = (live as? LiveState.Watching)?.status
}

/**
 * Aim (SPEC Screens › Aim): Live's tuner loop for the selected channel, each reading put on the compass at the
 * phone's heading. Optionally a second channel on a second tuner. Both tuners are released when the screen leaves.
 */
@HiltViewModel
class AimViewModel @Inject constructor(
    private val devices: DeviceRepository,
    private val appState: AppStateRepository,
    private val access: TunerAccess,
    private val leases: TunerLeases,
    private val clock: Clock,
    private val headings: HeadingSource,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(AimUiState(compass = headings.available))
    val state: StateFlow<AimUiState> = _state.asStateFlow()

    val settings: StateFlow<Settings> = settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    private val _events = MutableSharedFlow<AimEvent>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** New peaks and lock losses, for the haptics. */
    val events: SharedFlow<AimEvent> = _events.asSharedFlow()

    private var visible = false
    private var jobs: List<Job> = emptyList()
    private var secondJob: Job? = null

    init {
        viewModelScope.launch {
            val selection = loadSelection(devices, appState)
            _state.update { it.copy(selection = selection) }
            if (visible) launchAll()
        }
    }

    fun start() {
        visible = true
        launchAll()
    }

    fun stop() {
        visible = false
        (jobs + listOfNotNull(secondJob)).forEach { it.cancel() }
        jobs = emptyList()
        secondJob = null
    }

    fun resetPeak() = _state.update { it.copy(trace = it.trace.reset()) }

    fun retry() = launchAll()

    fun setTone(on: Boolean) = viewModelScope.launch { settingsRepository.setAimTone(on) }

    fun setHaptics(on: Boolean) = viewModelScope.launch { settingsRepository.setAimHaptics(on) }

    fun setTowers(bearings: List<Int>) = viewModelScope.launch { settingsRepository.setTowerBearings(bearings) }

    fun saveHome(latitude: Double, longitude: Double) = viewModelScope.launch { settingsRepository.setHome(latitude, longitude) }

    /** Watches [channel] on a second tuner; null stops it. */
    fun setSecond(channel: Channel?) {
        _state.update { it.copy(second = channel?.let { c -> SecondTuner(c) }) }
        launchSecond()
    }

    private fun launchAll() {
        val ready = state.value.selection as? Selection.Ready ?: return
        if (!visible) return
        val previous = jobs
        _state.update { it.copy(thresholds = settings.value.thresholds) }
        val session = session(ready.device)
        jobs = listOf(
            viewModelScope.launch {
                previous.forEach { it.cancelAndJoin() }
                launch { session.run(ready.channel, ready.device.tunerCount) }
                var last: TunerStatus? = null
                session.state.collect { live ->
                    val status = (live as? LiveState.Watching)?.status
                    var event: AimEvent? = null
                    _state.update { s ->
                        if (status == null || status === last) return@update s.copy(live = live)
                        val sample = SignalSample(status.isLocked, status.ss, status.snq, status.seq)
                        val (trace, e) = s.trace.add(sample, s.heading?.degrees)
                        event = e
                        s.copy(live = live, trace = trace)
                    }
                    last = status
                    event?.let { _events.tryEmit(it) }
                }
            },
            viewModelScope.launch {
                headings.headings().collect { h -> _state.update { it.copy(heading = h) } }
            },
        )
        launchSecond()
    }

    private fun launchSecond() {
        val previous = secondJob
        secondJob = null
        val ready = state.value.selection as? Selection.Ready
        val second = state.value.second
        if (ready == null || second == null || !visible) {
            previous?.cancel()
            return
        }
        val session = session(ready.device)
        secondJob = viewModelScope.launch {
            previous?.cancelAndJoin()
            launch { session.run(second.channel, ready.device.tunerCount) }
            session.state.collect { live ->
                _state.update { s -> if (s.second?.channel == second.channel) s.copy(second = s.second.copy(live = live)) else s }
            }
        }
    }

    private fun session(device: Device) = LiveSession(
        deviceId = device.deviceId,
        sessions = access.sessions(device),
        leases = leases,
        sink = { channel, rf, hz, band, atsc3 -> devices.recordTuned(channel, rf, hz, band, atsc3) },
        clock = clock,
        pollInterval = settings.value.pollIntervalMs.milliseconds,
    )
}
