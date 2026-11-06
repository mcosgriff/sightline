package io.cosgriff.sightline.ui.screens.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import io.cosgriff.sightline.data.device.DeviceDiagnostics
import io.cosgriff.sightline.data.device.DeviceRepository
import io.cosgriff.sightline.data.device.Diagnostics
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface DiagnosticsState {
    data object Loading : DiagnosticsState

    /** The device was forgotten while the screen was open. */
    data object NoDevice : DiagnosticsState

    data class Read(val diagnostics: Diagnostics, val releasing: Int? = null, val releaseFailed: Int? = null) : DiagnosticsState
}

/** Diagnostics for one device: reads everything every [REFRESH_MS] while visible. Read-only, except [release]. */
@HiltViewModel(assistedFactory = DiagnosticsViewModel.Factory::class)
class DiagnosticsViewModel @AssistedInject constructor(
    @Assisted private val deviceId: String,
    private val devices: DeviceRepository,
    private val diagnostics: DeviceDiagnostics,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(deviceId: String): DiagnosticsViewModel
    }

    private val _state = MutableStateFlow<DiagnosticsState>(DiagnosticsState.Loading)
    val state: StateFlow<DiagnosticsState> = _state.asStateFlow()

    private var ticker: Job? = null

    fun start() {
        if (ticker?.isActive == true) return
        ticker = viewModelScope.launch {
            while (isActive) {
                read()
                delay(REFRESH_MS)
            }
        }
    }

    fun stop() {
        ticker?.cancel()
        ticker = null
    }

    private suspend fun read() {
        val device = devices.device(deviceId)
        if (device == null) {
            _state.value = DiagnosticsState.NoDevice
            return
        }
        val read = diagnostics.read(device)
        _state.update { old -> (old as? DiagnosticsState.Read)?.copy(diagnostics = read) ?: DiagnosticsState.Read(read) }
    }

    /** Gives back a lock key Sightline left on [tuner] (a killed process, a release that didn't reach the device). */
    fun release(tuner: Int) {
        val current = _state.value as? DiagnosticsState.Read ?: return
        if (current.releasing != null) return
        _state.value = current.copy(releasing = tuner, releaseFailed = null)
        viewModelScope.launch {
            val done = diagnostics.releaseStale(current.diagnostics.device, tuner)
            read()
            _state.update { (it as? DiagnosticsState.Read)?.copy(releasing = null, releaseFailed = if (done) null else tuner) ?: it }
        }
    }

    private companion object {
        const val REFRESH_MS = 2_000L
    }
}
