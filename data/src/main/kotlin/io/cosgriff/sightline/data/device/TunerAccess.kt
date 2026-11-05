package io.cosgriff.sightline.data.device

import io.cosgriff.sightline.data.settings.AppStateRepository
import io.cosgriff.sightline.protocol.ControlClient
import io.cosgriff.sightline.protocol.HttpApi
import io.cosgriff.sightline.protocol.TunerSessions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

/** The tuners this app holds right now, by device ID and tuner number. */
@Singleton
class TunerLeases @Inject constructor() {
    private val _held = MutableStateFlow<Set<Pair<String, Int>>>(emptySet())
    val held: StateFlow<Set<Pair<String, Int>>> = _held.asStateFlow()

    fun add(deviceId: String, tuner: Int) = _held.update { it + (deviceId to tuner) }
    fun remove(deviceId: String, tuner: Int) = _held.update { it - (deviceId to tuner) }
}

/**
 * One control connection per device, shared by every screen (SPEC: one persistent TCP connection), and the
 * [TunerSessions] on it. Lock keys go to [AppStateRepository], so a killed process's claims are released at the
 * next start.
 */
@Singleton
class TunerAccess @Inject constructor(
    private val http: HttpApi,
    private val lockKeys: AppStateRepository,
) {
    private data class Entry(val address: String, val port: Int, val control: ControlClient, val sessions: TunerSessions)

    private val entries = mutableMapOf<String, Entry>()

    @Synchronized
    fun sessions(device: Device): TunerSessions = entry(device).sessions

    @Synchronized
    fun control(device: Device): ControlClient = entry(device).control

    private fun entry(device: Device): Entry {
        entries[device.deviceId]?.let { if (it.address == device.address && it.port == device.controlPort) return it }
        entries.remove(device.deviceId)?.control?.close()
        val control = ControlClient(InetAddress.getByName(device.address), device.controlPort)
        val sessions = TunerSessions(device.deviceId, device.tunerCount, control, http, lockKeys)
        return Entry(device.address, device.controlPort, control, sessions).also { entries[device.deviceId] = it }
    }
}
