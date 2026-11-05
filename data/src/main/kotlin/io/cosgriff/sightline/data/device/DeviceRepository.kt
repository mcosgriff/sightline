package io.cosgriff.sightline.data.device

import io.cosgriff.sightline.data.db.ChannelDao
import io.cosgriff.sightline.data.db.ChannelEntity
import io.cosgriff.sightline.data.db.DeviceDao
import io.cosgriff.sightline.data.db.DeviceEntity
import io.cosgriff.sightline.protocol.DiscoveredDevice
import io.cosgriff.sightline.protocol.Discovery
import io.cosgriff.sightline.protocol.HttpApi
import io.cosgriff.sightline.protocol.HttpResult
import io.cosgriff.sightline.protocol.Occupancy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.UnknownHostException
import java.time.Clock
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AddResult {
    data class Added(val device: Device) : AddResult

    /** The name didn't resolve, or isn't a host at all. */
    data object BadAddress : AddResult

    /** Something is there, but it isn't an HDHomeRun that answered. */
    data object NotFound : AddResult
}

/** Devices: found by discovery or added by address, kept in Room, with the Demo tuner in debug builds. */
@Singleton
class DeviceRepository @Inject constructor(
    private val devices: DeviceDao,
    private val channels: ChannelDao,
    private val discovery: Discovery,
    private val http: HttpApi,
    private val access: TunerAccess,
    private val leases: TunerLeases,
    private val clock: Clock,
    demoDevice: Optional<DemoDevice>,
) {
    private val demo: Device? = demoDevice.orElse(null)?.device

    /** Every known device, the Demo tuner last. */
    val all: Flow<List<Device>> = devices.observeAll().map { stored -> stored.map { it.toDevice() } + listOfNotNull(demo) }

    suspend fun device(deviceId: String): Device? =
        if (deviceId == demo?.deviceId) demo else devices.get(deviceId)?.toDevice()

    /** Broadcast discovery, then each device's `discover.json`. Returns the IDs that answered. */
    suspend fun scan(): Set<String> {
        val found = discovery.discover()
        for (d in found) save(d, addedAs = null)
        return found.map { it.deviceId }.toSet() + listOfNotNull(demo?.deviceId)
    }

    /**
     * Adds a device by IP or hostname (`hdhomerun.local`): a direct discover first, then `discover.json`, for
     * networks that drop UDP.
     */
    suspend fun addByAddress(text: String): AddResult {
        val host = text.trim().removePrefix("http://").removePrefix("https://").substringBefore('/').trim()
        if (host.isEmpty() || host.any { it.isWhitespace() }) return AddResult.BadAddress
        val address = withContext(Dispatchers.IO) {
            try {
                InetAddress.getByName(host)
            } catch (_: UnknownHostException) {
                null
            } catch (_: SecurityException) {
                null
            }
        } ?: return AddResult.BadAddress

        discovery.probe(address)?.let { return AddResult.Added(save(it, addedAs = host)) }
        val info = (http.deviceInfo("http://${address.hostAddress}") as? HttpResult.Ok)?.value ?: return AddResult.NotFound
        val found = DiscoveredDevice(
            deviceId = info.deviceId.uppercase(),
            tunerCount = info.tunerCount,
            baseUrl = info.baseUrl ?: "http://${address.hostAddress}",
            lineupUrl = info.lineupUrl,
            address = address,
        )
        return AddResult.Added(save(found, addedAs = host))
    }

    private suspend fun save(found: DiscoveredDevice, addedAs: String?): Device {
        val known = devices.get(found.deviceId)
        val info = (http.deviceInfo(found.baseUrl) as? HttpResult.Ok)?.value
        val entity = DeviceEntity(
            deviceId = found.deviceId,
            friendlyName = info?.friendlyName ?: known?.friendlyName ?: "HDHomeRun ${found.deviceId}",
            model = info?.modelNumber ?: known?.model ?: "",
            firmware = info?.firmwareVersion ?: known?.firmware ?: "",
            baseUrl = found.baseUrl,
            lineupUrl = found.lineupUrl ?: info?.lineupUrl ?: known?.lineupUrl,
            tunerCount = found.tunerCount.takeIf { it > 0 } ?: info?.tunerCount ?: known?.tunerCount ?: 0,
            address = found.address.hostAddress ?: known?.address ?: "",
            controlPort = found.controlPort,
            lastSeen = clock.millis(),
            addedAs = addedAs ?: known?.addedAs,
        )
        devices.upsert(entity)
        return entity.toDevice()
    }

    suspend fun forget(deviceId: String) = devices.delete(deviceId)

    /** Who has each tuner. Read-only: two gets per tuner. Null when the device doesn't answer. */
    suspend fun tunerUse(device: Device): List<TunerUse>? {
        val sessions = access.sessions(device)
        val held = leases.held.value
        val uses = (0 until device.tunerCount).map { tuner ->
            when (val o = sessions.occupancy(tuner)) {
                Occupancy.Idle -> TunerUse.Idle
                is Occupancy.Busy -> if ((device.deviceId to tuner) in held) TunerUse.Sightline else TunerUse.Other(o.holder)
                Occupancy.Unknown -> TunerUse.Unknown
            }
        }
        if (uses.all { it == TunerUse.Unknown }) return null
        if (!device.demo) devices.get(device.deviceId)?.let { devices.upsert(it.copy(lastSeen = clock.millis())) }
        return uses
    }

    /** Fetches the lineup into Room. False when the device didn't give one. */
    suspend fun refreshLineup(device: Device): Boolean {
        val url = device.lineupUrl ?: return false
        val lineup = (http.lineup(url) as? HttpResult.Ok)?.value ?: return false
        channels.replaceLineup(
            device.deviceId,
            lineup.mapIndexed { i, e -> ChannelEntity(device.deviceId, e.guideNumber, e.guideName, e.url, i) },
        )
        return true
    }

    fun channels(deviceId: String): Flow<List<Channel>> = channels.observe(deviceId).map { list -> list.map { it.toChannel() } }

    suspend fun channelList(deviceId: String): List<Channel> = channels.list(deviceId).map { it.toChannel() }

    /** Fills in what a tune revealed: the RF channel, frequency and band, and whether it is ATSC 3.0. */
    suspend fun recordTuned(channel: Channel, rf: Int?, hz: Long?, band: String?, atsc3: Boolean) =
        channels.recordTuned(channel.deviceId, channel.guideNumber, rf, hz, band, atsc3)
}
