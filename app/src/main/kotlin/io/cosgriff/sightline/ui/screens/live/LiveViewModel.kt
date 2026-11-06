package io.cosgriff.sightline.ui.screens.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.cosgriff.sightline.analysis.Thresholds
import io.cosgriff.sightline.data.device.Channel
import io.cosgriff.sightline.data.device.Device
import io.cosgriff.sightline.data.device.DeviceRepository
import io.cosgriff.sightline.data.device.TunerAccess
import io.cosgriff.sightline.data.device.TunerLeases
import io.cosgriff.sightline.data.live.LiveSession
import io.cosgriff.sightline.data.live.LiveState
import io.cosgriff.sightline.data.settings.AppStateRepository
import io.cosgriff.sightline.data.settings.Settings
import io.cosgriff.sightline.data.settings.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
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

/** What Live shows, before any tuner state: which device, and whether it has channels. */
sealed interface LiveSetup {
    /** Still reading the selection. */
    data object Loading : LiveSetup

    /** Nothing picked on Devices yet, or the picked device is gone. */
    data object NoDevice : LiveSetup

    /** The device gave no lineup (or didn't answer when asked). */
    data class NoChannels(val device: Device) : LiveSetup

    data class Ready(val device: Device, val channels: List<Channel>, val channel: Channel) : LiveSetup
}

data class LiveUiState(
    val setup: LiveSetup = LiveSetup.Loading,
    /** Null until the session starts (the screen isn't visible, or setup isn't ready). */
    val live: LiveState? = null,
    val thresholds: Thresholds = Thresholds.DEFAULT,
    val nowMs: Long = 0,
    /** The user let the tuner go with Disconnect; nothing is claimed until Connect or a new channel. */
    val disconnected: Boolean = false,
)

/**
 * Live (SPEC Screens › Live): holds one tuner while the screen is visible. [start] claims and polls, [stop]
 * releases; changing channel releases and claims again, trying the same tuner first.
 */
@HiltViewModel
class LiveViewModel @Inject constructor(
    private val devices: DeviceRepository,
    private val appState: AppStateRepository,
    private val access: TunerAccess,
    private val leases: TunerLeases,
    private val clock: Clock,
    settingsRepository: SettingsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(LiveUiState())
    val state: StateFlow<LiveUiState> = _state.asStateFlow()

    val settings: StateFlow<Settings> = settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    private var session: LiveSession? = null
    private var sessionFor: String? = null
    private var job: Job? = null
    private var stateJob: Job? = null
    private var visible = false

    init {
        viewModelScope.launch { loadSetup() }
    }

    private suspend fun loadSetup() {
        val selection = appState.liveSelection.first()
        val device = selection?.let { devices.device(it.deviceId) }
        if (device == null) {
            _state.update { it.copy(setup = LiveSetup.NoDevice) }
            return
        }
        // Fresh each time: stream URLs carry the device's address, which DHCP (or the Demo tuner's ports) can
        // change. The stored lineup stands in when the device doesn't answer.
        devices.refreshLineup(device)
        val channels = devices.channelList(device.deviceId)
        if (channels.isEmpty()) {
            _state.update { it.copy(setup = LiveSetup.NoChannels(device)) }
            return
        }
        val channel = channels.firstOrNull { it.guideNumber == selection.guideNumber } ?: channels.first()
        _state.update { it.copy(setup = LiveSetup.Ready(device, channels, channel)) }
        if (visible) launchSession()
    }

    /** The screen is visible: claim and poll. */
    fun start() {
        visible = true
        launchSession()
    }

    /** The screen left: release the tuner. */
    fun stop() {
        visible = false
        val running = job
        job = null
        // Release finishes even though nothing waits for it; the session's finally runs NonCancellable.
        running?.cancel()
    }

    /** Re-reads the lineup, for when it was empty. */
    fun reload() {
        val device = (state.value.setup as? LiveSetup.NoChannels)?.device
        _state.update { it.copy(setup = LiveSetup.Loading, live = null) }
        viewModelScope.launch {
            device?.let { devices.refreshLineup(it) }
            loadSetup()
        }
    }

    /** After a lost tuner or a refused channel: try again. */
    fun retry() = launchSession()

    /** Releases the tuner and stays off it while the screen is open, until [connect] or a channel change. */
    fun disconnect() {
        _state.update { it.copy(disconnected = true, live = null) }
        job?.cancel()
        job = null
    }

    fun connect() {
        _state.update { it.copy(disconnected = false) }
        launchSession()
    }

    fun next() = step(+1)
    fun previous() = step(-1)

    /** Jumps straight to a channel from the picker. */
    fun select(guideNumber: String) {
        val ready = state.value.setup as? LiveSetup.Ready ?: return
        val channel = ready.channels.firstOrNull { it.guideNumber == guideNumber } ?: return
        show(ready, channel)
    }

    private fun step(by: Int) {
        val ready = state.value.setup as? LiveSetup.Ready ?: return
        val i = ready.channels.indexOfFirst { it.guideNumber == ready.channel.guideNumber }
        show(ready, ready.channels[Math.floorMod(i + by, ready.channels.size)])
    }

    /** Picking a channel means watching it, so it connects again after a Disconnect. */
    private fun show(ready: LiveSetup.Ready, channel: Channel) {
        _state.update { it.copy(setup = ready.copy(channel = channel), live = null, disconnected = false) }
        viewModelScope.launch { appState.selectForLive(ready.device.deviceId, channel.guideNumber) }
        launchSession()
    }

    private fun launchSession() {
        val ready = state.value.setup as? LiveSetup.Ready ?: return
        if (!visible || state.value.disconnected) return
        val previous = job
        val live = sessionFor(ready.device)
        job = viewModelScope.launch {
            previous?.cancelAndJoin() // the old tuner is released before the next claim
            _state.update { it.copy(thresholds = settings.value.thresholds) }
            live.run(ready.channel, ready.device.tunerCount)
        }
    }

    /** One session per device, so the last tuner is remembered across channel changes. */
    private fun sessionFor(device: Device): LiveSession {
        session?.let { if (sessionFor == device.deviceId) return it }
        val created = LiveSession(
            deviceId = device.deviceId,
            sessions = access.sessions(device),
            leases = leases,
            sink = { channel, rf, hz, band, atsc3 -> devices.recordTuned(channel, rf, hz, band, atsc3) },
            clock = clock,
            pollInterval = settings.value.pollIntervalMs.milliseconds,
        )
        session = created
        sessionFor = device.deviceId
        stateJob?.cancel()
        stateJob = viewModelScope.launch {
            created.state.collect { s ->
                // A last reading landing after Disconnect mustn't bring the gauges back.
                _state.update { if (it.disconnected) it else it.copy(live = s, nowMs = clock.millis()) }
            }
        }
        return created
    }
}
