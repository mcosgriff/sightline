package io.cosgriff.sightline.protocol

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** One HDHomeRun that answered a discover. [deviceId] is the eight-digit upper-case hex ID, e.g. `1A2B3C4D`. */
data class DiscoveredDevice(
    val deviceId: String,
    val tunerCount: Int,
    val baseUrl: String,
    val lineupUrl: String?,
    val address: InetAddress,
    /** The control port. 65001 on a real unit; the Demo tuner listens wherever it was given a port. */
    val controlPort: Int = HDHOMERUN_PORT,
)

const val HDHOMERUN_PORT = 65001

/**
 * What discovery needs from the platform, so this module stays free of Android (ADR-0003). On Android: hold a
 * `WifiManager.MulticastLock` while replies are collected (many phones drop inbound broadcast without one), and
 * name the Wi-Fi subnet's directed broadcast address.
 */
interface DiscoveryEnvironment {
    suspend fun <T> withBroadcastReception(block: suspend () -> T): T

    /** Directed broadcast addresses to send to, besides `255.255.255.255`. */
    fun broadcastAddresses(): List<InetAddress>
}

/** For the JVM and tests: nothing to acquire, and only the given addresses. */
class PlainDiscoveryEnvironment(private val addresses: List<InetAddress> = emptyList()) : DiscoveryEnvironment {
    override suspend fun <T> withBroadcastReception(block: suspend () -> T): T = block()
    override fun broadcastAddresses() = addresses
}

/**
 * UDP discovery (SPEC "Discovery"): a wildcard discover to `255.255.255.255` and to each directed broadcast
 * address, replies collected for [collectFor], one result per device ID. Real units answer several times, once
 * per address they heard on, in two variants (captured in `docs/protocol-captures/discover-udp.txt`).
 */
class Discovery(
    private val environment: DiscoveryEnvironment,
    private val port: Int = HDHOMERUN_PORT,
    private val collectFor: Duration = 1500.milliseconds,
    private val includeGlobalBroadcast: Boolean = true,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Every device that answered a broadcast discover, in the order they first answered. */
    suspend fun discover(): List<DiscoveredDevice> = environment.withBroadcastReception {
        val targets = buildList {
            if (includeGlobalBroadcast) add(InetAddress.getByName("255.255.255.255"))
            addAll(environment.broadcastAddresses())
        }.distinct()
        exchange(targets)
    }

    /** Asks one address directly, for a typed IP or a resolved `hdhomerun.local`. Null when nothing answers. */
    suspend fun probe(address: InetAddress): DiscoveredDevice? = exchange(listOf(address)).firstOrNull()

    private suspend fun exchange(targets: List<InetAddress>): List<DiscoveredDevice> = withContext(io) {
        val found = LinkedHashMap<String, DiscoveredDevice>()
        DatagramSocket(null).use { socket ->
            socket.broadcast = true
            socket.bind(InetSocketAddress(0))
            val request = discoverRequest().encode()
            var sent = 0
            for (target in targets) {
                try {
                    socket.send(DatagramPacket(request, request.size, InetSocketAddress(target, port)))
                    sent++
                } catch (_: IOException) {
                    // An unreachable broadcast address (no route, interface down) shouldn't stop the others.
                }
            }
            if (sent == 0) return@withContext emptyList()

            val buffer = ByteArray(MAX_DATAGRAM)
            val started = TimeSource.Monotonic.markNow()
            while (true) {
                ensureActive()
                val left = collectFor - started.elapsedNow()
                if (!left.isPositive()) break
                socket.soTimeout = left.inWholeMilliseconds.coerceIn(1, POLL_MS).toInt()
                val datagram = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(datagram)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val bytes = datagram.data.copyOfRange(datagram.offset, datagram.offset + datagram.length)
                val device = parseReply(bytes, datagram.address) ?: continue
                found.putIfAbsent(device.deviceId, device)
            }
        }
        found.values.toList()
    }

    companion object {
        private const val MAX_DATAGRAM = 2048
        private const val POLL_MS = 100L

        fun discoverRequest() = Packet(
            PacketType.DISCOVER_REQUEST,
            listOf(Tlv.uint32(Tag.DEVICE_TYPE, DeviceType.TUNER), Tlv.uint32(Tag.DEVICE_ID, DeviceType.WILDCARD)),
        )

        /**
         * A discover reply from [from], or null when it isn't one from a tuner. Without a base URL tag (older
         * firmware) the base URL is `http://<address>`.
         */
        fun parseReply(bytes: ByteArray, from: InetAddress): DiscoveredDevice? {
            val packet = (Packet.decode(bytes) as? DecodeResult.Ok)?.packet ?: return null
            if (packet.type != PacketType.DISCOVER_REPLY) return null
            if (packet[Tag.DEVICE_TYPE]?.uint32() != DeviceType.TUNER) return null
            val id = packet[Tag.DEVICE_ID]?.uint32() ?: return null
            return DiscoveredDevice(
                deviceId = formatDeviceId(id),
                tunerCount = packet[Tag.TUNER_COUNT]?.uint()?.toInt() ?: 0,
                baseUrl = packet[Tag.BASE_URL]?.string() ?: "http://${from.hostAddress}",
                lineupUrl = packet[Tag.LINEUP_URL]?.string(),
                address = from,
            )
        }

        fun formatDeviceId(id: Long) = "%08X".format(id)
    }
}
