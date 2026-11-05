package io.cosgriff.sightline.ui.screens.monitor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.cosgriff.sightline.analysis.Thresholds
import io.cosgriff.sightline.data.device.Channel
import io.cosgriff.sightline.data.device.Device
import io.cosgriff.sightline.data.device.DeviceRepository
import io.cosgriff.sightline.data.monitor.MonitorPlan
import io.cosgriff.sightline.data.monitor.MonitorStatus
import io.cosgriff.sightline.data.settings.AppStateRepository
import io.cosgriff.sightline.data.settings.SettingsRepository
import io.cosgriff.sightline.monitor.MonitorController
import io.cosgriff.sightline.ui.screens.Selection
import io.cosgriff.sightline.ui.screens.loadSelection
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

/** How long to monitor (SPEC Screens › Monitor setup: 1 h to 72 h, or until stopped). Null hours: until stopped. */
enum class MonitorDuration(val hours: Int?) { H1(1), H2(2), H4(4), H8(8), H12(12), H24(24), H48(48), H72(72), UNTIL_STOPPED(null) }

/** Poll every second, or every 5 s to save battery (SPEC Screens › Monitor setup). */
val MONITOR_POLL_CHOICES = listOf(1_000L, 5_000L)

/** Seconds on each channel in rotate mode; 30 s is the default. */
val MONITOR_DWELL_CHOICES = listOf(15, 30, 60, 120)

/** The setup form (SPEC Screens › Monitor setup). [selected] are guide numbers in the order picked. */
data class MonitorSetup(
    val device: Device,
    val devices: List<Device>,
    val channels: List<Channel>,
    val selected: List<String>,
    val rotate: Boolean = false,
    /** A tuner the user picked, or null for any idle one. */
    val tuner: Int? = null,
    val dwellS: Int = 30,
    val duration: MonitorDuration = MonitorDuration.H12,
    val pollMs: Long = 1_000,
    /** A reading under this snq counts towards a dip; the poor threshold from Settings to start with. */
    val dipSnq: Int,
    val fetchWeather: Boolean = false,
    val notifyDips: Boolean = false,
) {
    val canStart: Boolean get() = selected.isNotEmpty()
}

/** What the setup form can show. */
sealed interface SetupState {
    data object Loading : SetupState

    /** No device picked on Devices yet. */
    data object NoDevice : SetupState

    data class NoChannels(val device: Device) : SetupState

    data class Ready(val setup: MonitorSetup) : SetupState
}

data class MonitorUiState(
    val setup: SetupState = SetupState.Loading,
    /** The session running, or the one just ended; null shows the setup. */
    val status: MonitorStatus? = null,
    val thresholds: Thresholds = Thresholds.DEFAULT,
)

/**
 * Monitor (SPEC Screens › Monitor setup and running): the setup form, starting the foreground service with it, and
 * the running session as [MonitorController] reports it.
 */
@HiltViewModel
class MonitorViewModel @Inject constructor(
    private val devices: DeviceRepository,
    private val appState: AppStateRepository,
    private val controller: MonitorController,
    settingsRepository: SettingsRepository,
    val clock: Clock,
) : ViewModel() {
    private val setup = MutableStateFlow<SetupState>(SetupState.Loading)
    private val _state = MutableStateFlow(MonitorUiState())
    val state: StateFlow<MonitorUiState> = _state.asStateFlow()

    private val settings = settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch { setup.collect { s -> _state.update { it.copy(setup = s) } } }
        viewModelScope.launch { controller.status.collect { s -> _state.update { it.copy(status = s) } } }
        viewModelScope.launch {
            val saved = settingsRepository.settings.first()
            val thresholds = saved.thresholds
            _state.update { it.copy(thresholds = thresholds) }
            setup.value = when (val selection = loadSelection(devices, appState)) {
                Selection.NoDevice -> SetupState.NoDevice
                is Selection.NoChannels -> SetupState.NoChannels(selection.device)
                is Selection.Ready -> SetupState.Ready(
                    MonitorSetup(
                        device = selection.device,
                        devices = devices.all.first(),
                        channels = selection.channels,
                        selected = listOf(selection.channel.guideNumber),
                        dipSnq = thresholds.snqMarginal,
                        pollMs = saved.pollIntervalMs,
                    ),
                )
            }
        }
    }

    private fun edit(change: (MonitorSetup) -> MonitorSetup) =
        setup.update { s -> if (s is SetupState.Ready) SetupState.Ready(change(s.setup)) else s }

    fun selectDevice(deviceId: String) {
        val current = (setup.value as? SetupState.Ready)?.setup ?: return
        if (deviceId == current.device.deviceId) return
        viewModelScope.launch {
            val device = devices.device(deviceId) ?: return@launch
            devices.refreshLineup(device)
            val channels = devices.channelList(deviceId)
            setup.value = if (channels.isEmpty()) {
                SetupState.NoChannels(device)
            } else {
                SetupState.Ready(current.copy(device = device, channels = channels, selected = listOf(channels.first().guideNumber), tuner = null))
            }
        }
    }

    /** In hold mode picks [guideNumber]; in rotate mode adds or removes it. */
    fun toggleChannel(guideNumber: String) = edit { s ->
        when {
            !s.rotate -> s.copy(selected = listOf(guideNumber))
            guideNumber in s.selected -> s.copy(selected = s.selected - guideNumber)
            else -> s.copy(selected = s.selected + guideNumber)
        }
    }

    /** Back to holding one channel keeps the first picked. */
    fun setRotate(rotate: Boolean) = edit { s -> s.copy(rotate = rotate, selected = if (rotate) s.selected else s.selected.take(1)) }

    fun setTuner(tuner: Int?) = edit { it.copy(tuner = tuner) }
    fun setDwell(seconds: Int) = edit { it.copy(dwellS = seconds) }
    fun setDuration(duration: MonitorDuration) = edit { it.copy(duration = duration) }
    fun setPoll(ms: Long) = edit { it.copy(pollMs = ms) }

    /** Between 10 and the good threshold: a dip line above "good" would make every good reading a dip. */
    fun setDipSnq(snq: Int) = edit { it.copy(dipSnq = snq.coerceIn(MIN_DIP_SNQ, state.value.thresholds.snqGood)) }

    fun setFetchWeather(on: Boolean) = edit { it.copy(fetchWeather = on) }
    fun setNotifyDips(on: Boolean) = edit { it.copy(notifyDips = on) }

    fun start() {
        val s = (setup.value as? SetupState.Ready)?.setup?.takeIf { it.canStart } ?: return
        val thresholds = settings.value?.thresholds ?: state.value.thresholds
        val byGuide = s.channels.associateBy { it.guideNumber }
        controller.start(
            MonitorPlan(
                device = s.device,
                channels = s.selected.mapNotNull { byGuide[it] },
                tuner = s.tuner,
                dwellMs = s.dwellS * 1_000L,
                durationMs = s.duration.hours?.let { it * 3_600_000L },
                pollIntervalMs = s.pollMs,
                thresholds = thresholds.copy(snqMarginal = s.dipSnq),
                fetchWeather = s.fetchWeather,
                notifyDips = s.notifyDips,
            ),
        )
    }

    fun stop() = controller.stop()
    fun dismiss() = controller.dismiss()

    suspend fun batteryExemptionAsked() = appState.batteryExemptionAsked()
    fun markBatteryExemptionAsked() = viewModelScope.launch { appState.markBatteryExemptionAsked() }
    suspend fun notificationsAsked() = appState.notificationsAsked()
    fun markNotificationsAsked() = viewModelScope.launch { appState.markNotificationsAsked() }

    private companion object {
        const val MIN_DIP_SNQ = 10
    }
}
