package io.cosgriff.sightline.ui.screens.protocoldebug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.cosgriff.sightline.data.di.ApplicationScope
import io.cosgriff.sightline.debugtools.DemoTuner
import io.cosgriff.sightline.protocol.ClaimResult
import io.cosgriff.sightline.protocol.ClaimedTuner
import io.cosgriff.sightline.protocol.ControlClient
import io.cosgriff.sightline.protocol.ControlResult
import io.cosgriff.sightline.protocol.DiscoveredDevice
import io.cosgriff.sightline.protocol.Discovery
import io.cosgriff.sightline.protocol.Hold
import io.cosgriff.sightline.protocol.HttpApi
import io.cosgriff.sightline.protocol.HttpResult
import io.cosgriff.sightline.protocol.LineupEntry
import io.cosgriff.sightline.protocol.LostReason
import io.cosgriff.sightline.protocol.Poll
import io.cosgriff.sightline.protocol.TuneTarget
import io.cosgriff.sightline.protocol.TunerSessions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DebugDevice(val device: DiscoveredDevice, val demo: Boolean)

/** One tuner's latest `status` reply: the raw line, or the device's error message, or no answer. */
data class TunerLine(val tuner: Int, val raw: String?, val error: String?)

sealed interface ClaimView {
    data object None : ClaimView
    data object Claiming : ClaimView
    data class Held(val tuner: Int, val stream: Boolean, val raw: String?) : ClaimView
    data class Lost(val tuner: Int, val reason: LostReason) : ClaimView
    data class Failed(val why: ClaimFailure) : ClaimView
}

enum class ClaimFailure { NONE_FREE, UNKNOWN_CHANNEL, UNREACHABLE }

data class ProtocolDebugState(
    val scanning: Boolean = false,
    val devices: List<DebugDevice> = emptyList(),
    val selectedId: String? = null,
    val tunerLines: List<TunerLine> = emptyList(),
    val lineup: List<LineupEntry> = emptyList(),
    val claim: ClaimView = ClaimView.None,
)

/**
 * Milestone 1's debug screen: lists what discovery finds (plus the Demo tuner), prints every tuner's raw status
 * once a second, read-only, and can claim one idle tuner to show the claim, poll and release path working.
 */
@HiltViewModel
class ProtocolDebugViewModel @Inject constructor(
    private val discovery: Discovery,
    private val http: HttpApi,
    @param:ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {
    private val _state = MutableStateFlow(ProtocolDebugState(devices = listOf(DebugDevice(DemoTuner.device, demo = true))))
    val state: StateFlow<ProtocolDebugState> = _state.asStateFlow()

    private var control: ControlClient? = null
    private var sessions: TunerSessions? = null
    private var claimed: ClaimedTuner? = null
    private var ticker: Job? = null

    fun scan() {
        if (_state.value.scanning) return
        _state.update { it.copy(scanning = true) }
        viewModelScope.launch {
            val found = discovery.discover().map { DebugDevice(it, demo = false) }
            _state.update { it.copy(scanning = false, devices = found + DebugDevice(DemoTuner.device, demo = true)) }
        }
    }

    fun select(deviceId: String) {
        val device = _state.value.devices.firstOrNull { it.device.deviceId == deviceId }?.device ?: return
        dropDevice()
        val client = ControlClient(device.address, device.controlPort)
        control = client
        sessions = TunerSessions(device.deviceId, device.tunerCount, client, http)
        _state.update { it.copy(selectedId = deviceId, tunerLines = emptyList(), lineup = emptyList(), claim = ClaimView.None) }
        ticker = viewModelScope.launch {
            device.lineupUrl?.let { url ->
                (http.lineup(url) as? HttpResult.Ok)?.let { lineup -> _state.update { it.copy(lineup = lineup.value) } }
            }
            while (isActive) {
                val lines = (0 until device.tunerCount).map { tuner ->
                    when (val r = client.get("/tuner$tuner/status")) {
                        is ControlResult.Value -> TunerLine(tuner, r.value, null)
                        is ControlResult.Rejected -> TunerLine(tuner, null, r.error.message)
                        is ControlResult.Unreachable -> TunerLine(tuner, null, null)
                    }
                }
                _state.update { it.copy(tunerLines = lines) }
                claimed?.let { pollClaimed(it) }
                delay(1_000)
            }
        }
    }

    private suspend fun pollClaimed(tuner: ClaimedTuner) {
        val stream = tuner.hold is Hold.Stream
        val view = when (val poll = tuner.poll()) {
            is Poll.Reading -> ClaimView.Held(tuner.tuner, stream, poll.status.raw)
            is Poll.Unparseable -> ClaimView.Held(tuner.tuner, stream, poll.raw)
            is Poll.Unreachable -> ClaimView.Held(tuner.tuner, stream, null)
            is Poll.Lost -> {
                claimed = null
                tuner.release()
                ClaimView.Lost(tuner.tuner, poll.reason)
            }
        }
        _state.update { it.copy(claim = view) }
    }

    fun claim(entry: LineupEntry) {
        val sessions = sessions ?: return
        if (claimed != null || _state.value.claim == ClaimView.Claiming) return
        _state.update { it.copy(claim = ClaimView.Claiming) }
        viewModelScope.launch {
            val view = when (val result = sessions.claim(viewModelScope, TuneTarget.Virtual(entry.guideNumber, entry.url))) {
                is ClaimResult.Claimed -> {
                    claimed = result.tuner
                    ClaimView.Held(result.tuner.tuner, result.tuner.hold is Hold.Stream, null)
                }
                is ClaimResult.NoneFree -> ClaimView.Failed(ClaimFailure.NONE_FREE)
                ClaimResult.UnknownChannel -> ClaimView.Failed(ClaimFailure.UNKNOWN_CHANNEL)
                ClaimResult.Unreachable -> ClaimView.Failed(ClaimFailure.UNREACHABLE)
            }
            _state.update { it.copy(claim = view) }
        }
    }

    fun release() {
        val tuner = claimed ?: return
        claimed = null
        appScope.launch { tuner.release() }
        _state.update { it.copy(claim = ClaimView.None) }
    }

    /** Stops polling and gives back any claimed tuner, on the app scope so it finishes after this screen is gone. */
    private fun dropDevice() {
        ticker?.cancel()
        val tuner = claimed
        val client = control
        claimed = null
        control = null
        sessions = null
        appScope.launch {
            tuner?.release()
            client?.close()
        }
    }

    override fun onCleared() = dropDevice()
}
