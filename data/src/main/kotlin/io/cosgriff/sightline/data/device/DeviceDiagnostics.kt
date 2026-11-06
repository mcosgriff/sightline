package io.cosgriff.sightline.data.device

import io.cosgriff.sightline.data.settings.AppStateRepository
import io.cosgriff.sightline.protocol.DeviceInfo
import io.cosgriff.sightline.protocol.DeviceSnapshot
import io.cosgriff.sightline.protocol.HttpApi
import io.cosgriff.sightline.protocol.HttpResult
import io.cosgriff.sightline.protocol.TunerObserver
import io.cosgriff.sightline.protocol.TunerSnapshot
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** Who holds a tuner, as far as the device and this app can tell. */
sealed interface TunerHolder {
    data object Idle : TunerHolder

    /** A screen or the monitor in this app holds it right now. */
    data object ThisApp : TunerHolder

    /**
     * The device says this phone's address holds it, but nothing in this app does: a tuner left behind (a stream
     * still open, a lock key not given back), or another app on the phone. [staleKey] is true when Sightline still
     * has the lock key, so it can release it.
     */
    data class ThisPhone(val staleKey: Boolean) : TunerHolder

    /** Another client: a DVR, a TV app. [address] null when the device doesn't say. */
    data class Other(val address: String?) : TunerHolder

    /** Tuned with no lock and no stream target: someone tuned it and walked away, or it's still letting go. */
    data object TunedNoHolder : TunerHolder

    data object Unknown : TunerHolder
}

/** One reading of the Diagnostics screen. */
data class Diagnostics(
    val device: Device,
    val snapshot: DeviceSnapshot,
    /** `discover.json` now; null when it didn't answer. Never includes DeviceAuth. */
    val info: DeviceInfo?,
    val holders: List<TunerHolder>,
    /** Tuners this device has a lock key remembered for (claimed now, or left by a killed process). */
    val rememberedKeys: Set<Int>,
    val readAtMs: Long,
)

/**
 * Collects what the Diagnostics screen shows: the device's own values (read-only, see [DeviceSnapshot.read]),
 * `discover.json`, and what this app holds. Also releases a stale lock key on request, the same way the start-up
 * recovery does, which the device refuses on a tuner someone else holds.
 */
@Singleton
class DeviceDiagnostics @Inject constructor(
    private val access: TunerAccess,
    private val http: HttpApi,
    private val leases: TunerLeases,
    private val appState: AppStateRepository,
    private val clock: Clock,
) {
    suspend fun read(device: Device): Diagnostics {
        val snapshot = DeviceSnapshot.read(access.control(device), device.tunerCount)
        val info = (http.deviceInfo(device.baseUrl) as? HttpResult.Ok)?.value
        val keys = appState.heldLocks().filter { it.deviceId == device.deviceId }.map { it.tuner }.toSet()
        val ours = leases.held.value.filter { it.first == device.deviceId }.map { it.second }.toSet()
        val holders = snapshot.tuners.map { holderOf(it, snapshot.localAddress, it.tuner in ours, it.tuner in keys) }
        return Diagnostics(device, snapshot, info, holders, keys, clock.millis())
    }

    /** Releases the lock key Sightline remembers for [tuner], if it isn't in use by this app. True when done. */
    suspend fun releaseStale(device: Device, tuner: Int): Boolean {
        if (leases.held.value.contains(device.deviceId to tuner)) return false
        val key = appState.heldLocks().firstOrNull { it.deviceId == device.deviceId && it.tuner == tuner } ?: return false
        return access.sessions(device).releaseStale(tuner, key.lockKey)
    }

    companion object {
        fun holderOf(tuner: TunerSnapshot, localAddress: String?, ours: Boolean, keyRemembered: Boolean): TunerHolder {
            if (ours) return TunerHolder.ThisApp
            val status = tuner.status ?: return TunerHolder.Unknown
            val lock = tuner.lockHolder
            val streamHost = tuner.target?.let(TunerObserver::streamHost)
            val holder = lock ?: streamHost
            return when {
                holder == null && status.isIdle -> TunerHolder.Idle
                holder == null -> TunerHolder.TunedNoHolder
                holder == localAddress -> TunerHolder.ThisPhone(staleKey = keyRemembered)
                else -> TunerHolder.Other(holder)
            }
        }
    }
}
