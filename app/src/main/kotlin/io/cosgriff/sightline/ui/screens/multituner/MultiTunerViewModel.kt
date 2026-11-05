package io.cosgriff.sightline.ui.screens.multituner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.cosgriff.sightline.analysis.Thresholds
import io.cosgriff.sightline.data.device.Channel
import io.cosgriff.sightline.data.device.Device
import io.cosgriff.sightline.data.device.DeviceRepository
import io.cosgriff.sightline.data.device.TunerAccess
import io.cosgriff.sightline.data.device.TunerLeases
import io.cosgriff.sightline.data.device.TunerUse
import io.cosgriff.sightline.data.live.LiveSession
import io.cosgriff.sightline.data.live.LiveState
import io.cosgriff.sightline.data.live.MultiTunerPlan
import io.cosgriff.sightline.data.settings.AppStateRepository
import io.cosgriff.sightline.data.settings.Settings
import io.cosgriff.sightline.data.settings.SettingsRepository
import io.cosgriff.sightline.ui.screens.Selection
import io.cosgriff.sightline.ui.screens.loadSelection
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** One tuner's cell in the grid. */
sealed interface TunerCell {
    val tuner: Int

    /** Not run by this screen. [use] is who had it when last asked; [spare] marks the one kept free for a DVR. */
    data class Off(override val tuner: Int, val use: TunerUse, val spare: Boolean = false) : TunerCell

    /** Run by this screen on [channel]; [live] is its session's state, null before it starts. */
    data class On(override val tuner: Int, val channel: Channel, val live: LiveState? = null) : TunerCell
}

data class MultiTunerUiState(
    val selection: Selection? = null,
    val cells: List<TunerCell> = emptyList(),
    val thresholds: Thresholds = Thresholds.DEFAULT,
)

/**
 * Multi-tuner (SPEC Screens › 5): one cell per tuner of Live's device, each on its own channel, so a problem on
 * one tuner or all of them shows at a glance. On first showing, starts every idle tuner but one ([MultiTunerPlan]);
 * the user starts, retunes or stops each cell. Tuners another client holds are only shown, never taken. Leaving
 * the screen releases everything; coming back starts the same cells again.
 */
@HiltViewModel
class MultiTunerViewModel @Inject constructor(
    private val devices: DeviceRepository,
    private val appState: AppStateRepository,
    private val access: TunerAccess,
    private val leases: TunerLeases,
    private val clock: Clock,
    settingsRepository: SettingsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(MultiTunerUiState())
    val state: StateFlow<MultiTunerUiState> = _state.asStateFlow()

    val settings: StateFlow<Settings> = settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    private val jobs = mutableMapOf<Int, Job>()
    private var refresher: Job? = null
    private var visible = false
    private var planned = false

    init {
        viewModelScope.launch {
            val selection = loadSelection(devices, appState)
            _state.update { it.copy(selection = selection) }
            if (visible) begin()
        }
    }

    /** The screen is visible: plan on the first time, otherwise start the cells that were on. */
    fun start() {
        visible = true
        begin()
    }

    /** The screen left: release every tuner. Cells keep their channels for when it comes back. */
    fun stop() {
        visible = false
        refresher?.cancel()
        refresher = null
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }

    /** Starts [tuner] on [channel], or moves it there. */
    fun tune(tuner: Int, channel: Channel) {
        val device = ready()?.device ?: return
        setCell(TunerCell.On(tuner, channel))
        if (visible) launchCell(device, tuner, channel)
    }

    /** Stops [tuner] and gives it back. */
    fun stopTuner(tuner: Int) {
        jobs.remove(tuner)?.cancel()
        setCell(TunerCell.Off(tuner, TunerUse.Idle))
    }

    /** After a lost tuner or a refused channel: try the cell again. */
    fun retry(tuner: Int) {
        val cell = _state.value.cells.firstOrNull { it.tuner == tuner } as? TunerCell.On ?: return
        tune(tuner, cell.channel)
    }

    private fun ready(): Selection.Ready? = _state.value.selection as? Selection.Ready

    private fun begin() {
        val ready = ready() ?: return
        _state.update { it.copy(thresholds = settings.value.thresholds) }
        viewModelScope.launch {
            if (!planned) {
                planned = true
                plan(ready)
            }
            if (!visible) return@launch
            for (cell in _state.value.cells) if (cell is TunerCell.On) launchCell(ready.device, cell.tuner, cell.channel)
            refresher?.cancel()
            refresher = viewModelScope.launch { refreshOffCells(ready.device) }
        }
    }

    private suspend fun plan(ready: Selection.Ready) {
        val device = ready.device
        // Live lets its tuner go as this screen opens; wait a moment so that tuner counts as idle.
        withTimeoutOrNull(OWN_RELEASE_WAIT) { leases.held.first { held -> held.none { it.first == device.deviceId } } }
        val uses = devices.tunerUse(device) ?: List(device.tunerCount) { TunerUse.Unknown }
        val plan = MultiTunerPlan.initial(uses, ready.channels, ready.channel)
        val spare = uses.indices.lastOrNull { (uses[it] == TunerUse.Idle || uses[it] == TunerUse.Sightline) && it !in plan }
        val cells = uses.indices.map { t ->
            plan[t]?.let { TunerCell.On(t, it) } ?: TunerCell.Off(t, if (uses[t] == TunerUse.Sightline) TunerUse.Idle else uses[t], spare = t == spare)
        }
        _state.update { it.copy(cells = cells) }
    }

    private fun launchCell(device: Device, tuner: Int, channel: Channel) {
        val previous = jobs[tuner]
        val session = LiveSession(
            deviceId = device.deviceId,
            sessions = access.sessions(device),
            leases = leases,
            sink = { c, rf, hz, band, atsc3 -> devices.recordTuned(c, rf, hz, band, atsc3) },
            clock = clock,
            pollInterval = settings.value.pollIntervalMs.milliseconds,
        )
        jobs[tuner] = viewModelScope.launch {
            previous?.cancelAndJoin() // the cell's old channel is released before the new claim
            val watcher = launch {
                session.state.collect { s ->
                    // A retuned cell ignores its old session's last words.
                    if (s != null) updateOn(tuner) { if (it.channel.guideNumber == s.channel.guideNumber) it.copy(live = s) else it }
                }
            }
            try {
                session.run(channel, device.tunerCount, only = tuner)
            } finally {
                watcher.cancel()
            }
        }
    }

    /** Off cells say who holds their tuner; asked again every [REFRESH] (two reads per tuner, nothing claimed). */
    private suspend fun refreshOffCells(device: Device) {
        while (true) {
            delay(REFRESH)
            if (_state.value.cells.none { it is TunerCell.Off }) continue
            val uses = devices.tunerUse(device) ?: continue
            _state.update { s ->
                s.copy(
                    cells = s.cells.map { c ->
                        if (c is TunerCell.Off && c.tuner < uses.size) c.copy(use = if (uses[c.tuner] == TunerUse.Sightline) TunerUse.Idle else uses[c.tuner]) else c
                    },
                )
            }
        }
    }

    private fun setCell(cell: TunerCell) = _state.update { s -> s.copy(cells = s.cells.map { if (it.tuner == cell.tuner) cell else it }) }

    private fun updateOn(tuner: Int, change: (TunerCell.On) -> TunerCell.On) = _state.update { s ->
        s.copy(cells = s.cells.map { if (it is TunerCell.On && it.tuner == tuner) change(it) else it })
    }

    override fun onCleared() = stop()

    private companion object {
        val OWN_RELEASE_WAIT = 3.seconds
        val REFRESH = 30.seconds
    }
}
