package io.cosgriff.sightline.ui.screens.sweep

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.cosgriff.sightline.analysis.SweepComparison
import io.cosgriff.sightline.analysis.Thresholds
import io.cosgriff.sightline.data.device.DeviceRepository
import io.cosgriff.sightline.data.device.TunerAccess
import io.cosgriff.sightline.data.device.TunerLeases
import io.cosgriff.sightline.data.settings.AppStateRepository
import io.cosgriff.sightline.data.settings.Settings
import io.cosgriff.sightline.data.settings.SettingsRepository
import io.cosgriff.sightline.data.sweep.SavedSweep
import io.cosgriff.sightline.data.sweep.SweepInfo
import io.cosgriff.sightline.data.sweep.SweepOutcome
import io.cosgriff.sightline.data.sweep.SweepProgress
import io.cosgriff.sightline.data.sweep.SweepRepository
import io.cosgriff.sightline.data.sweep.SweepRunner
import io.cosgriff.sightline.ui.screens.Selection
import io.cosgriff.sightline.ui.screens.loadSelection
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

/** Where the sweep itself is. */
sealed interface SweepRun {
    /** Not started, or stopped when the screen left; starts when the screen shows. */
    data object Idle : SweepRun

    data class Running(val progress: SweepProgress?, val channelCount: Int, val dwellMs: Long) : SweepRun

    /** The device stopped answering; nothing was saved. */
    data object Failed : SweepRun

    data object Done : SweepRun
}

/** A finished sweep and, when there is one, the device's sweep before it with the comparison. */
data class SweepResult(val saved: SavedSweep, val previous: SavedSweep?, val comparison: SweepComparison?)

/** The compare view: [result]'s sweep against [other]. */
data class SweepCompare(val other: SavedSweep, val comparison: SweepComparison)

data class SweepUiState(
    val selection: Selection? = null,
    val run: SweepRun = SweepRun.Idle,
    val result: SweepResult? = null,
    val compare: SweepCompare? = null,
    /** The device's saved sweeps, newest first, for picking what to compare with. */
    val sweeps: List<SweepInfo> = emptyList(),
    val thresholds: Thresholds = Thresholds.DEFAULT,
)

/**
 * Sweep (SPEC Screens › Sweep): runs as soon as the screen shows, holding one tuner at a time; leaving the screen
 * stops it and releases the tuner, and nothing partial is saved. A finished sweep is a `SWEEP` session, compared
 * with the one before it.
 */
@HiltViewModel
class SweepViewModel @Inject constructor(
    private val devices: DeviceRepository,
    private val appState: AppStateRepository,
    private val access: TunerAccess,
    private val leases: TunerLeases,
    private val clock: Clock,
    private val sweeps: SweepRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(SweepUiState())
    val state: StateFlow<SweepUiState> = _state.asStateFlow()

    val settings: StateFlow<Settings> = settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    private var visible = false
    private var job: Job? = null

    init {
        viewModelScope.launch {
            val selection = loadSelection(devices, appState)
            _state.update { it.copy(selection = selection) }
            if (selection is Selection.Ready) {
                launch { sweeps.sweeps(selection.device.deviceId).collect { list -> _state.update { it.copy(sweeps = list) } } }
            }
            if (visible) begin()
        }
    }

    fun start() {
        visible = true
        if (state.value.run == SweepRun.Idle) begin()
    }

    fun stop() {
        visible = false
        if (state.value.run is SweepRun.Running) {
            job?.cancel()
            job = null
            _state.update { it.copy(run = SweepRun.Idle) }
        }
    }

    fun sweepAgain() = begin()

    /** Opens the compare view against [id], or the sweep before this one. */
    fun compare(id: Long? = null) {
        val result = state.value.result ?: return
        viewModelScope.launch {
            val thresholds = state.value.thresholds
            val other = id?.let { sweeps.load(it, thresholds) } ?: result.previous ?: return@launch
            val comparison = SweepComparison.of(other.summary.channels, result.saved.summary.channels)
            _state.update { it.copy(compare = SweepCompare(other, comparison)) }
        }
    }

    fun closeCompare() = _state.update { it.copy(compare = null) }

    private fun begin() {
        val ready = state.value.selection as? Selection.Ready ?: return
        if (!visible) return
        val settings = settings.value
        val previous = job
        job = viewModelScope.launch {
            previous?.cancel()
            _state.update {
                it.copy(run = SweepRun.Running(null, ready.channels.size, settings.sweepDwellMs), thresholds = settings.thresholds, compare = null)
            }
            val runner = SweepRunner(
                deviceId = ready.device.deviceId,
                sessions = access.sessions(ready.device),
                leases = leases,
                sink = { channel, rf, hz, band, atsc3 -> devices.recordTuned(channel, rf, hz, band, atsc3) },
                clock = clock,
                pollInterval = settings.pollIntervalMs.milliseconds,
                dwell = settings.sweepDwellMs.milliseconds,
            )
            val progress = launch {
                runner.progress.collect { p ->
                    _state.update { s -> (s.run as? SweepRun.Running)?.let { s.copy(run = it.copy(progress = p)) } ?: s }
                }
            }
            val outcome = runner.run(ready.channels, ready.device.tunerCount)
            progress.cancel()
            when (outcome) {
                SweepOutcome.Unreachable -> _state.update { it.copy(run = SweepRun.Failed) }
                is SweepOutcome.Finished -> {
                    val id = sweeps.save(ready.device.deviceId, outcome, settings.sweepDwellMs, settings.pollIntervalMs)
                    val saved = sweeps.load(id, settings.thresholds) ?: return@launch
                    val before = sweeps.sweeps(ready.device.deviceId).first()
                        .firstOrNull { it.id != id && it.startedAt <= saved.info.startedAt }
                        ?.let { sweeps.load(it.id, settings.thresholds) }
                    val comparison = before?.let { SweepComparison.of(it.summary.channels, saved.summary.channels) }
                    _state.update { it.copy(run = SweepRun.Done, result = SweepResult(saved, before, comparison)) }
                }
            }
        }
    }
}
