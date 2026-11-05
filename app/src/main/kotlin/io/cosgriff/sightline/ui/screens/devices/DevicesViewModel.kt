package io.cosgriff.sightline.ui.screens.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.cosgriff.sightline.data.device.AddResult
import io.cosgriff.sightline.data.device.Device
import io.cosgriff.sightline.data.device.DeviceRepository
import io.cosgriff.sightline.data.device.TunerUse
import io.cosgriff.sightline.data.settings.AppStateRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject

/** One device card. [tuners] null: not checked yet, or the device didn't answer ([online] says which). */
data class DeviceCard(val device: Device, val online: Boolean?, val tuners: List<TunerUse>?)

enum class AddFailure { BAD_ADDRESS, NOT_FOUND }

data class DevicesUiState(
    val cards: List<DeviceCard> = emptyList(),
    val scanning: Boolean = false,
    /** UTC epoch ms of the last finished scan, null before the first. */
    val scannedAt: Long? = null,
    val adding: Boolean = false,
    val addFailure: AddFailure? = null,
    /** When this state was made, from the injected clock: "scanned 3 min ago" counts from it. */
    val nowMs: Long = 0,
)

/** Scans on start, keeps each card's tuner use fresh every 5 s while the screen is visible. */
@HiltViewModel
class DevicesViewModel @Inject constructor(
    private val repository: DeviceRepository,
    private val appState: AppStateRepository,
    private val clock: Clock,
) : ViewModel() {
    private val scan = MutableStateFlow(DevicesUiState())
    private val use = MutableStateFlow<Map<String, List<TunerUse>?>>(emptyMap())

    val state: StateFlow<DevicesUiState> = combine(scan, repository.all, use) { s, devices, uses ->
        s.copy(
            nowMs = clock.millis(),
            cards = devices.map { d ->
                // Online once it answered the last check; null before the first.
                val online = if (d.deviceId in uses) uses[d.deviceId] != null else null
                DeviceCard(d, online, uses[d.deviceId])
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DevicesUiState())

    private var refresher: Job? = null

    /** The screen is visible: scan once, then keep tuner use current. Needs the network permission. */
    fun start() {
        if (refresher?.isActive == true) return
        refresher = viewModelScope.launch {
            if (scan.value.scannedAt == null) runScan()
            while (isActive) {
                delay(REFRESH_MS)
                refreshUse()
            }
        }
    }

    fun stop() {
        refresher?.cancel()
        refresher = null
    }

    fun rescan() {
        if (scan.value.scanning) return
        viewModelScope.launch { runScan() }
    }

    private suspend fun runScan() {
        scan.update { it.copy(scanning = true) }
        repository.scan()
        refreshUse()
        scan.update { it.copy(scanning = false, scannedAt = clock.millis()) }
    }

    private suspend fun refreshUse() {
        val devices = repository.all.first()
        val next = devices.associate { it.deviceId to repository.tunerUse(it) }
        use.value = next
    }

    fun add(address: String) {
        if (scan.value.adding) return
        scan.update { it.copy(adding = true, addFailure = null) }
        viewModelScope.launch {
            val failure = when (repository.addByAddress(address)) {
                is AddResult.Added -> null
                AddResult.BadAddress -> AddFailure.BAD_ADDRESS
                AddResult.NotFound -> AddFailure.NOT_FOUND
            }
            scan.update { it.copy(adding = false, addFailure = failure) }
            if (failure == null) refreshUse()
        }
    }

    /** Live opens on this device; it picks the first idle tuner and the first channel. */
    fun openLive(deviceId: String, then: () -> Unit) {
        viewModelScope.launch {
            appState.selectForLive(deviceId, null)
            then()
        }
    }

    private companion object {
        const val REFRESH_MS = 5_000L
    }
}
