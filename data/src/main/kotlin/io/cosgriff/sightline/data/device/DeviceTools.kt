package io.cosgriff.sightline.data.device

import io.cosgriff.sightline.data.di.ApplicationScope
import io.cosgriff.sightline.protocol.ControlResult
import io.cosgriff.sightline.protocol.FirmwareDownload
import io.cosgriff.sightline.protocol.FirmwareLookup
import io.cosgriff.sightline.protocol.HttpApi
import io.cosgriff.sightline.protocol.HttpResult
import io.cosgriff.sightline.protocol.LineupStatus
import io.cosgriff.sightline.protocol.SiliconDustFirmware
import io.cosgriff.sightline.protocol.UpgradeResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton

/** Why Device tools won't start a channel scan or a firmware update now (SPEC "Device tools"). */
sealed interface ToolsBlock {
    /** Another client (a DVR, a TV app) uses these tuners. [holders] maps tuner to its IP, null when unknown. */
    data class InUse(val holders: Map<Int, String?>) : ToolsBlock

    /** Sightline itself holds these tuners: Live, Aim, Sweep, Multi-tuner or Monitor. */
    data class ThisApp(val tuners: List<Int>) : ToolsBlock

    /** The device didn't say whether its tuners are free. */
    data object Unreachable : ToolsBlock

    /** The device is running a channel scan (no update during one). */
    data object Scanning : ToolsBlock

    /** A firmware update to this device is under way. */
    data object Updating : ToolsBlock
}

/** Starting the device's channel scan. */
sealed interface ScanStart {
    data object Started : ScanStart
    data class Blocked(val block: ToolsBlock) : ScanStart
    data object Failed : ScanStart
}

/** Where a firmware update is. Lives in the app scope, so it carries on if the screen closes. */
sealed interface UpdateStep {
    val version: String

    data class Downloading(override val version: String, val read: Long, val total: Long?) : UpdateStep
    data class Uploading(override val version: String, val sent: Int, val total: Int) : UpdateStep

    /** The device took the image and is installing it and restarting; waiting for it to come back. */
    data class Restarting(override val version: String) : UpdateStep
    data class Done(override val version: String) : UpdateStep
    data class Failed(override val version: String, val why: UpdateFailure, val detail: String? = null) : UpdateStep

    val running: Boolean get() = this is Downloading || this is Uploading || this is Restarting
}

enum class UpdateFailure {
    /** A tuner was in use (or the device didn't answer) when the update was about to start or upload. */
    BLOCKED,

    /** No firmware name from the device to look the file up by. */
    NO_FIRMWARE_NAME,

    /** SiliconDust has no file for that version. */
    NOT_FOUND,

    /** SiliconDust couldn't be reached, or the file didn't arrive whole. */
    DOWNLOAD_FAILED,

    /** The device refused the image ([UpdateStep.Failed.detail] has its message). */
    REJECTED,

    /** The upload didn't finish. The device keeps its firmware: without the end marker it discards the pieces. */
    UPLOAD_FAILED,

    /** The device took the image but didn't come back on the new version in time. */
    NOT_BACK,
}

/** Where firmware files come from for a device: SiliconDust, or the Demo tuner's own fake server. */
fun interface FirmwareFiles {
    fun forDevice(device: Device): SiliconDustFirmware
}

/** SiliconDust for real units (ADR-0005); the Demo tuner serves its own made-up files. */
@Singleton
class DefaultFirmwareFiles @Inject constructor(client: OkHttpClient, demo: Optional<DemoDevice>) : FirmwareFiles {
    private val siliconDust = SiliconDustFirmware(client)
    private val demoFiles = demo.orElse(null)?.firmwareBaseUrl?.let { SiliconDustFirmware(client, it) }

    override fun forDevice(device: Device) = if (device.demo && demoFiles != null) demoFiles else siliconDust
}

/**
 * The two things Sightline changes on a device as a whole (SPEC "Device tools"): the device's own channel scan,
 * and a firmware update from SiliconDust. Both are refused while any tuner is in use, by another client or by
 * Sightline, since a scan takes a tuner and changes the lineup a DVR uses, and an update restarts the device.
 */
@Singleton
class DeviceTools @Inject constructor(
    private val devices: DeviceRepository,
    private val access: TunerAccess,
    private val http: HttpApi,
    private val files: FirmwareFiles,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val _updates = MutableStateFlow<Map<String, UpdateStep>>(emptyMap())

    /** Each device's latest firmware update, by device ID, running or finished, for as long as the app lives. */
    val updates: StateFlow<Map<String, UpdateStep>> = _updates.asStateFlow()

    /** How long to wait after an accepted upload before looking (libhdhomerun waits 10 s), then how often and how long. */
    internal var settleMs = 10_000L
    internal var pollMs = 2_000L
    internal var giveUpMs = 180_000L

    /** Null when nothing stands in the way of a scan or update; otherwise the first reason. Gets only. */
    suspend fun block(device: Device): ToolsBlock? {
        if (_updates.value[device.deviceId]?.running == true) return ToolsBlock.Updating
        return tunerBlock(device)
    }

    /** What the tuners say alone: in use by another client or by Sightline, or no answer. */
    private suspend fun tunerBlock(device: Device): ToolsBlock? {
        val uses = devices.tunerUse(device) ?: return ToolsBlock.Unreachable
        val others = uses.withIndex().filter { it.value is TunerUse.Other }
        if (others.isNotEmpty()) return ToolsBlock.InUse(others.associate { it.index to (it.value as TunerUse.Other).holder })
        val ours = uses.withIndex().filter { it.value == TunerUse.Sightline }.map { it.index }
        if (ours.isNotEmpty()) return ToolsBlock.ThisApp(ours)
        if (uses.any { it == TunerUse.Unknown }) return ToolsBlock.Unreachable
        return null
    }

    /** The device's scan state; null when it didn't answer. */
    suspend fun lineupStatus(device: Device): LineupStatus? = (http.lineupStatus(device.baseUrl) as? HttpResult.Ok)?.value

    suspend fun startScan(device: Device, source: String): ScanStart {
        block(device)?.let { return ScanStart.Blocked(it) }
        return when (http.startLineupScan(device.baseUrl, source)) {
            is HttpResult.Ok -> ScanStart.Started
            is HttpResult.Failed -> ScanStart.Failed
        }
    }

    /** Stops the device's scan. It keeps the lineup it had. True when the device took it. */
    suspend fun abortScan(device: Device): Boolean = http.abortLineupScan(device.baseUrl) is HttpResult.Ok

    /** Fetches the lineup a finished scan made into Room. */
    suspend fun refreshLineup(device: Device): Boolean = devices.refreshLineup(device)

    /** The firmware name to look files up by (`FirmwareName`, else `/sys/model`); null when neither answers. */
    suspend fun firmwareName(device: Device): String? =
        (http.deviceInfo(device.baseUrl) as? HttpResult.Ok)?.value?.firmwareName
            ?: (access.control(device).get("/sys/model") as? ControlResult.Value)?.value?.trim()?.takeIf { it.isNotEmpty() }

    /** Asks SiliconDust for the newest firmware for [firmwareName]. Only on the user's request. */
    suspend fun latestFirmware(device: Device, firmwareName: String): FirmwareLookup =
        files.forDevice(device).latest(firmwareName)

    /**
     * Starts updating [device] to [version] in the app scope: download, check no tuner is in use, upload, wait for
     * the restart. Progress is in [updates]. False when an update to this device is already running.
     */
    fun startUpdate(device: Device, version: String): Boolean {
        var started = false
        _updates.update { all ->
            if (all[device.deviceId]?.running == true) return@update all
            started = true
            all + (device.deviceId to UpdateStep.Downloading(version, 0, null))
        }
        if (started) scope.launch { _updates.set(device.deviceId, update(device, version)) }
        return started
    }

    /** Forgets a finished update's result, so the screen stops showing it. */
    fun dismissUpdate(deviceId: String) = _updates.update { all -> if (all[deviceId]?.running == true) all else all - deviceId }

    private suspend fun update(device: Device, version: String): UpdateStep {
        val id = device.deviceId
        fun failed(why: UpdateFailure, detail: String? = null) = UpdateStep.Failed(version, why, detail)
        if (blockIgnoringThisUpdate(device) != null) return failed(UpdateFailure.BLOCKED)
        val name = firmwareName(device) ?: return failed(UpdateFailure.NO_FIRMWARE_NAME)

        val image = when (val d = files.forDevice(device).download(name, version) { read, total ->
            _updates.set(id, UpdateStep.Downloading(version, read, total))
        }) {
            is FirmwareDownload.Ok -> d.image
            FirmwareDownload.NotFound, FirmwareDownload.BadName -> return failed(UpdateFailure.NOT_FOUND)
            FirmwareDownload.TooLarge, is FirmwareDownload.Failed -> return failed(UpdateFailure.DOWNLOAD_FAILED)
        }

        // Checked again: a recording may have started during the download.
        if (blockIgnoringThisUpdate(device) != null) return failed(UpdateFailure.BLOCKED)
        _updates.set(id, UpdateStep.Uploading(version, 0, image.size))
        var shown = 0
        val result = access.control(device).upgrade(image) { sent ->
            // Every 1 % or so: a FLEX 4K image is over 2,000 pieces.
            if (sent - shown >= image.size / 100 || sent == image.size) {
                shown = sent
                _updates.set(id, UpdateStep.Uploading(version, sent, image.size))
            }
        }
        when (result) {
            UpgradeResult.Accepted -> Unit
            is UpgradeResult.Rejected -> return failed(UpdateFailure.REJECTED, result.message)
            is UpgradeResult.Unreachable -> return failed(UpdateFailure.UPLOAD_FAILED)
        }

        _updates.set(id, UpdateStep.Restarting(version))
        delay(settleMs)
        // It may answer on the old firmware while it still installs, so wait to see the new one.
        var waited = 0L
        while (waited < giveUpMs) {
            val running = (http.deviceInfo(device.baseUrl) as? HttpResult.Ok)?.value?.firmwareVersion
            if (running == version) {
                if (!device.demo) devices.recordFirmware(id, version)
                return UpdateStep.Done(version)
            }
            delay(pollMs)
            waited += pollMs
        }
        return failed(UpdateFailure.NOT_BACK)
    }

    /** [block] for the update that is asking: its own run doesn't count, a running channel scan does. */
    private suspend fun blockIgnoringThisUpdate(device: Device): ToolsBlock? {
        if (lineupStatus(device)?.scanning == true) return ToolsBlock.Scanning
        return tunerBlock(device)
    }

    private fun MutableStateFlow<Map<String, UpdateStep>>.set(deviceId: String, step: UpdateStep) =
        update { it + (deviceId to step) }
}
