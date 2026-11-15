package io.cosgriff.sightline.ui.screens.devicetools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import io.cosgriff.sightline.data.device.DemoDevices
import io.cosgriff.sightline.data.device.Device
import io.cosgriff.sightline.data.device.DeviceRepository
import io.cosgriff.sightline.data.device.DeviceTools
import io.cosgriff.sightline.data.device.ScanStart
import io.cosgriff.sightline.data.device.ToolsBlock
import io.cosgriff.sightline.data.device.UpdateStep
import io.cosgriff.sightline.protocol.FirmwareLookup
import io.cosgriff.sightline.protocol.FirmwareVersions
import io.cosgriff.sightline.protocol.HttpApi
import io.cosgriff.sightline.protocol.HttpResult
import io.cosgriff.sightline.protocol.LineupStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Optional

/** The newest firmware SiliconDust has, asked only when the user taps Check. */
sealed interface LatestCheck {
    data object NotChecked : LatestCheck
    data object Checking : LatestCheck
    data class Found(val version: String) : LatestCheck

    /** SiliconDust has no files for this firmware name. */
    data object UnknownFirmware : LatestCheck
    data object Failed : LatestCheck
}

/** Starting a scan, as far as this screen asked. */
enum class ScanRequest { IDLE, STARTING, FAILED }

data class DeviceToolsState(
    val device: Device? = null,
    /** The device was forgotten while the screen was open. */
    val missing: Boolean = false,
    /** Null when nothing is in the way of a scan or update. */
    val block: ToolsBlock? = null,
    /** The device's scan state; null before the first read or when it didn't answer. */
    val lineup: LineupStatus? = null,
    val channelCount: Int = 0,
    /** The source picked for the next scan; the device's own last one until the user picks. */
    val source: String? = null,
    val scanRequest: ScanRequest = ScanRequest.IDLE,
    /** A scan this screen watched has finished and the lineup was re-read. */
    val scanFinished: Boolean = false,
    val installed: String? = null,
    val firmwareName: String? = null,
    /** `UpgradeAvailable` from the device's own check. */
    val offered: String? = null,
    val latest: LatestCheck = LatestCheck.NotChecked,
    val update: UpdateStep? = null,
    val loaded: Boolean = false,
) {
    /** The version Install would put on: SiliconDust's newest when checked and newer, else the device's offer. */
    val target: String?
        get() {
            val now = installed ?: return null
            val newest = (latest as? LatestCheck.Found)?.version?.takeIf { FirmwareVersions.isNewer(it, now) }
            return newest ?: offered?.takeIf { FirmwareVersions.isNewer(it, now) }
        }

    val canScan: Boolean
        get() = block == null && lineup?.canScan == true && scanRequest != ScanRequest.STARTING && update?.running != true

    val canUpdate: Boolean get() = block == null && target != null && update?.running != true && lineup?.scanning != true
}

/** Device tools for one device: reads every [REFRESH_MS] while visible; scans and updates only on request. */
@HiltViewModel(assistedFactory = DeviceToolsViewModel.Factory::class)
class DeviceToolsViewModel @AssistedInject constructor(
    @Assisted private val deviceId: String,
    private val devices: DeviceRepository,
    private val tools: DeviceTools,
    private val http: HttpApi,
    private val demo: Optional<DemoDevices>,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(deviceId: String): DeviceToolsViewModel
    }

    private val _state = MutableStateFlow(DeviceToolsState())
    val state: StateFlow<DeviceToolsState> = _state.asStateFlow()

    private var ticker: Job? = null
    private var sawScanning = false

    init {
        viewModelScope.launch { devices.channels(deviceId).collect { list -> _state.update { it.copy(channelCount = list.size) } } }
        viewModelScope.launch { tools.updates.collect { all -> _state.update { it.copy(update = all[deviceId]) } } }
    }

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
            _state.update { it.copy(missing = true, loaded = true) }
            return
        }
        val info = (http.deviceInfo(device.baseUrl) as? HttpResult.Ok)?.value
        val lineup = tools.lineupStatus(device)
        val block = tools.block(device)
        if (lineup?.scanning == true) sawScanning = true
        val finished = sawScanning && lineup != null && !lineup.scanning
        if (finished) {
            sawScanning = false
            tools.refreshLineup(device)
        }
        _state.update {
            it.copy(
                device = device,
                block = block,
                lineup = lineup,
                source = it.source ?: lineup?.source ?: lineup?.sourceList?.firstOrNull(),
                scanFinished = it.scanFinished || finished,
                installed = info?.firmwareVersion ?: it.installed ?: device.firmware.ifEmpty { null },
                firmwareName = info?.firmwareName ?: it.firmwareName,
                offered = info?.upgradeAvailable,
                loaded = true,
            )
        }
    }

    fun pickSource(source: String) = _state.update { it.copy(source = source) }

    fun startScan() {
        val s = _state.value
        val device = s.device ?: return
        val source = s.source ?: return
        if (!s.canScan) return
        _state.update { it.copy(scanRequest = ScanRequest.STARTING, scanFinished = false) }
        viewModelScope.launch {
            val result = tools.startScan(device, source)
            if (result == ScanStart.Started) sawScanning = true
            _state.update { it.copy(scanRequest = if (result == ScanStart.Started) ScanRequest.IDLE else ScanRequest.FAILED) }
            read()
        }
    }

    fun stopScan() {
        val device = _state.value.device ?: return
        viewModelScope.launch {
            tools.abortScan(device)
            sawScanning = false
            read()
        }
    }

    /** Asks SiliconDust for the newest firmware. The only call to SiliconDust that isn't a download. */
    fun checkLatest() {
        val s = _state.value
        val device = s.device ?: return
        if (s.latest == LatestCheck.Checking) return
        _state.update { it.copy(latest = LatestCheck.Checking) }
        viewModelScope.launch {
            val name = s.firmwareName ?: tools.firmwareName(device)
            val result = when (val r = name?.let { tools.latestFirmware(device, it) }) {
                is FirmwareLookup.Latest -> LatestCheck.Found(r.version)
                FirmwareLookup.UnknownFirmware, FirmwareLookup.BadName -> LatestCheck.UnknownFirmware
                is FirmwareLookup.Failed, null -> LatestCheck.Failed
            }
            _state.update { it.copy(latest = result, firmwareName = name ?: it.firmwareName) }
        }
    }

    fun install() {
        val s = _state.value
        val device = s.device ?: return
        val version = s.target ?: return
        if (!s.canUpdate) return
        tools.startUpdate(device, version)
    }

    fun dismissUpdate() = tools.dismissUpdate(deviceId)

    /** Demo only: ends the made-up player's hold so the tools can be tried. */
    fun stopDemoPlayer() {
        if (_state.value.device?.demo != true) return
        demo.orElse(null)?.stopDemoPlayer(deviceId)
        viewModelScope.launch { read() }
    }

    private companion object {
        const val REFRESH_MS = 2_000L
    }
}
