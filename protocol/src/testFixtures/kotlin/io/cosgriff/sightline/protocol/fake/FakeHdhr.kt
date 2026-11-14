package io.cosgriff.sightline.protocol.fake

import io.cosgriff.sightline.protocol.DecodeResult
import io.cosgriff.sightline.protocol.DeviceType
import io.cosgriff.sightline.protocol.FrameResult
import io.cosgriff.sightline.protocol.Packet
import io.cosgriff.sightline.protocol.PacketType
import io.cosgriff.sightline.protocol.RfChannels
import io.cosgriff.sightline.protocol.Tag
import io.cosgriff.sightline.protocol.Tlv
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** One channel in the fake lineup. [atsc3] channels tune only over HTTP, as on a real FLEX 4K. */
data class FakeChannel(
    val guideNumber: String,
    val guideName: String,
    val rf: Int,
    val pattern: SignalPattern,
    val atsc3: Boolean = false,
    /** Other programs in the same transport stream, for `streaminfo`. */
    val subchannels: List<String> = emptyList(),
)

/**
 * An in-process HDHomeRun: UDP discovery, the TCP control protocol and the HTTP endpoints, on loopback ports.
 * It behaves like the FLEX 4K captured in `docs/protocol-captures/`: the same replies, error texts and lock
 * rules, ATSC 3.0 only over HTTP and only on the first [atsc3Tuners] tuners. Signals follow each channel's
 * [SignalPattern] from the moment it was tuned, on [clock] (milliseconds).
 *
 * Used by the protocol tests and, in debug builds, as the Demo tuner.
 */
class FakeHdhr(
    val deviceId: Long = 0x1D3E0001,
    val tunerCount: Int = 4,
    val atsc3Tuners: Int = 2,
    val channels: List<FakeChannel> = demoLineup(),
    private val clock: () -> Long = System::currentTimeMillis,
    // IPv4 loopback by name: getLoopbackAddress() is ::1 on Android, which would need brackets in every URL.
    private val bindAddress: InetAddress = InetAddress.getByName("127.0.0.1"),
    /** 0 uses the control port's number, as a real unit does. */
    discoveryPort: Int = 0,
) : Closeable {
    private val control = ServerSocket(0, 50, bindAddress)

    // Like a real unit, discovery (UDP) and control (TCP) share one port number, so a discovered device's
    // control port is the port discovery used.
    private val udp = DatagramSocket(InetSocketAddress(bindAddress, if (discoveryPort == 0) control.localPort else discoveryPort))
    private val http = ServerSocket(0, 50, bindAddress)
    private val connections = CopyOnWriteArrayList<Socket>()

    @Volatile
    private var running = true

    /** While true the device answers nothing, like a unit unplugged or off the network. */
    @Volatile
    var vanished = false
        set(value) {
            field = value
            if (value) connections.forEach { runCatching { it.close() } }
        }

    val discoveryPort: Int get() = udp.localPort
    val controlPort: Int get() = control.localPort
    val httpPort: Int get() = http.localPort
    val host: String get() = bindAddress.hostAddress
    val baseUrl: String get() = if (':' in host) "http://[$host]:$httpPort" else "http://$host:$httpPort"
    val deviceIdHex: String get() = "%08X".format(deviceId)

    private val tuners = List(tunerCount) { TunerState() }
    private val lock = Any()

    private class TunerState {
        var lockKey: Long? = null
        var lockHolder: String? = null
        var channel: FakeChannel? = null
        var viaRf: Boolean = false
        var tunedAt: Long = 0
        var stream: Socket? = null
        var streamTarget: String? = null

        /** Damaged packets since the tune, for `debug`'s `ts te`: grows while seq is below 100. */
        var transportErrors: Long = 0
        var countedAt: Long = 0

        /** Another client's stream losing packets on the network: `net err` grows this much a second. */
        var networkLossPerSecond: Long = 0
        var networkErrors: Long = 0
    }

    /** How many sets reached the device, so read-only callers can be checked. */
    @Volatile
    var setsReceived: Int = 0
        private set

    /** Makes the next discover.json offer [version] as `UpgradeAvailable`; null takes it away. */
    @Volatile
    var upgradeAvailable: String? = null

    /** The firmware running now (`/sys/version`, `FirmwareVersion`). An accepted upload changes it after a restart. */
    @Volatile
    var firmwareVersion: String = "20260326"

    /** The newest firmware the fake download server (paths under `/hdhomerun/`, as SiliconDust's) offers. */
    @Volatile
    var latestFirmware: String = "20261107"

    /** How long an accepted upload keeps the device off the network while it "installs and restarts". */
    @Volatile
    var restartMs: Long = 3_000

    /** Complete uploads received, accepted or not, so tests can check one was or wasn't sent. */
    @Volatile
    var uploadsReceived: Int = 0
        private set

    @Volatile
    private var pendingFirmware: String? = null

    /** The Ethernet link `/sys/debug` reports (`<Mbit/s><f|h>`), as on the captured unit by default. */
    @Volatile
    var ethernetLink: String = "100f"

    /** How long the device's own channel scan runs. A real FLEX 4K takes minutes; the Demo is quicker. */
    @Volatile
    var scanMs: Long = 20_000

    /** Channel scans started with `lineup.post`, so tests can check one was or wasn't. */
    @Volatile
    var scansStarted: Int = 0
        private set

    private var scanStartedAt: Long? = null
    private var scanSource = "Antenna"

    init {
        thread(isDaemon = true, name = "fake-hdhr-udp") { serveDiscovery() }
        thread(isDaemon = true, name = "fake-hdhr-control") { accept(control, ::serveControl) }
        thread(isDaemon = true, name = "fake-hdhr-http") { accept(http, ::serveHttp) }
    }

    /**
     * Another client takes [tuner], the way a DVR does with an HTTP stream: lock held by [byIp], tuned to [channel].
     * [networkLossPerSecond] makes its stream fail to reach the client that often (`debug`'s `net err`).
     */
    fun takeTuner(tuner: Int, byIp: String, channel: FakeChannel = channels.first(), networkLossPerSecond: Long = 0): Unit = synchronized(lock) {
        tuners[tuner].run {
            this.networkLossPerSecond = networkLossPerSecond
            lockKey = -1 // a key Sightline can't know
            lockHolder = byIp
            this.channel = channel
            viaRf = false
            tunedAt = clock()
            streamTarget = "http://$byIp:5004"
        }
    }

    /** Ends another client's use of [tuner], or Sightline's, leaving it idle. */
    fun freeTuner(tuner: Int): Unit = synchronized(lock) { clearTuner(tuners[tuner]) }

    /** For assertions: the lock key on [tuner], null when unlocked. */
    fun lockKeyOf(tuner: Int): Long? = synchronized(lock) { tuners[tuner].lockKey }

    fun isIdle(tuner: Int): Boolean = synchronized(lock) { tuners[tuner].let { it.channel == null && it.lockKey == null } }

    override fun close() {
        running = false
        runCatching { udp.close() }
        runCatching { control.close() }
        runCatching { http.close() }
        connections.forEach { runCatching { it.close() } }
    }

    // --- Discovery ---

    private fun serveDiscovery() {
        val buffer = ByteArray(2048)
        while (running) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                udp.receive(packet)
            } catch (_: IOException) {
                if (!running) return else continue
            }
            if (vanished) continue
            val request = Packet.decode(packet.data.copyOfRange(0, packet.length)) as? DecodeResult.Ok ?: continue
            if (request.packet.type != PacketType.DISCOVER_REQUEST) continue
            val type = request.packet[Tag.DEVICE_TYPE]?.uint32()
            if (type != DeviceType.TUNER && type != DeviceType.WILDCARD) continue
            val reply = Packet(
                PacketType.DISCOVER_REPLY,
                listOf(
                    Tlv.uint32(Tag.DEVICE_TYPE, DeviceType.TUNER),
                    Tlv.uint32(Tag.DEVICE_ID, deviceId),
                    Tlv(Tag.DEVICE_AUTH, "DEMODEMODEMODEMODEMODEMO".toByteArray()),
                    Tlv(Tag.BASE_URL, baseUrl.toByteArray()),
                    Tlv(Tag.TUNER_COUNT, byteArrayOf(tunerCount.toByte())),
                    Tlv(Tag.LINEUP_URL, "$baseUrl/lineup.json".toByteArray()),
                ),
            ).encode()
            runCatching { udp.send(DatagramPacket(reply, reply.size, packet.socketAddress)) }
        }
    }

    // --- Control ---

    private fun accept(server: ServerSocket, serve: (Socket) -> Unit) {
        while (running) {
            val socket = try {
                server.accept()
            } catch (_: IOException) {
                if (!running) return else continue
            }
            if (vanished) {
                socket.close()
                continue
            }
            connections += socket
            thread(isDaemon = true, name = "fake-hdhr-conn") {
                try {
                    socket.use { serve(it) }
                } catch (_: IOException) {
                    // Client went away.
                } finally {
                    connections -= socket
                }
            }
        }
    }

    private fun serveControl(socket: Socket) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val client = socket.inetAddress.hostAddress
        val upload = Upload()
        while (running) {
            val header = input.readNBytes(Packet.HEADER)
            if (header.size < Packet.HEADER) return
            val rest = input.readNBytes(Packet.frameSize(header) - Packet.HEADER)
            if (vanished) return
            val frame = Packet.unframe(header + rest) as? FrameResult.Ok ?: return
            if (frame.type == PacketType.UPGRADE_REQUEST) {
                // Pieces get no reply; the end marker gets one once the image is checked.
                val image = upload.take(frame.body) ?: continue
                val error = acceptUpload(image)
                val tlvs = error?.let { listOf(Tlv.string(Tag.ERROR_MESSAGE, "ERROR: $it")) } ?: emptyList()
                output.write(Packet(PacketType.UPGRADE_REPLY, tlvs).encode())
                output.flush()
                if (error == null) restart()
                continue
            }
            val request = (Packet.decode(header + rest) as? DecodeResult.Ok)?.packet ?: return
            val name = request[Tag.GETSET_NAME]?.string() ?: return
            val value = request[Tag.GETSET_VALUE]?.string()
            val key = request[Tag.LOCK_KEY]?.uint32()
            val reply = when (val result = handle(name, value, key, client)) {
                is Reply.Value -> listOf(Tlv.string(Tag.GETSET_NAME, name), Tlv.string(Tag.GETSET_VALUE, result.value))
                is Reply.Error -> listOf(Tlv.string(Tag.ERROR_MESSAGE, "ERROR: ${result.message}"))
            }
            output.write(Packet(PacketType.GETSET_REPLY, reply).encode())
            output.flush()
        }
    }

    /** One connection's firmware upload: pieces in order, each a uint32 offset then bytes, ended by `0xFFFFFFFF`. */
    private class Upload {
        private val bytes = ByteArrayOutputStream()
        private var broken = false

        /** Adds one piece; returns the whole image at the end marker (empty when a piece came out of order). */
        fun take(body: ByteArray): ByteArray? {
            if (body.size < 4) return null
            val offset = ByteBuffer.wrap(body, 0, 4).int
            if (offset == -1 && body.size == 4) {
                val image = if (broken) ByteArray(0) else bytes.toByteArray()
                bytes.reset()
                broken = false
                return image
            }
            if (offset != bytes.size()) broken = true
            bytes.write(body, 4, body.size - 4)
            return null
        }
    }

    /** Checks an uploaded image the way the fake's own files are built ([firmwareImage]); null when accepted. */
    private fun acceptUpload(image: ByteArray): String? {
        uploadsReceived++
        val header = String(image, 0, minOf(image.size, 96), Charsets.US_ASCII).substringBefore('\n').split(' ')
        if (header.size != 3 || header[0] != IMAGE_MAGIC || header[1] != FIRMWARE_NAME) return "invalid image"
        if (!image.contentEquals(firmwareImage(header[1], header[2]))) return "invalid image"
        pendingFirmware = header[2]
        return null
    }

    /** Like a real unit after an upload: off the network for [restartMs], back on the new firmware, tuners idle. */
    private fun restart() {
        thread(isDaemon = true, name = "fake-hdhr-restart") {
            Thread.sleep(200)
            vanished = true
            synchronized(lock) {
                tuners.forEach(::clearTuner)
                scanStartedAt = null
            }
            Thread.sleep(restartMs)
            pendingFirmware?.let { firmwareVersion = it }
            pendingFirmware = null
            upgradeAvailable = null
            vanished = false
        }
    }

    private sealed interface Reply {
        data class Value(val value: String) : Reply
        data class Error(val message: String) : Reply
    }

    private fun handle(name: String, value: String?, key: Long?, client: String): Reply = synchronized(lock) {
        when (name) {
            "/sys/model" -> return Reply.Value("hdhomerun_dvr_atsc3")
            "/sys/hwmodel" -> return Reply.Value("HDFX-4K")
            "/sys/version" -> return Reply.Value(firmwareVersion)
            "/sys/features" -> return Reply.Value(FEATURES)
            "/sys/debug" -> return Reply.Value(sysDebug())
        }
        val match = TUNER_PATH.matchEntire(name) ?: return UNKNOWN
        val index = match.groupValues[1].toInt()
        val tuner = tuners.getOrNull(index) ?: return UNKNOWN
        val item = match.groupValues[2]
        // As a FLEX 4K does while it runs its own channel scan: every tuner, every get and set.
        if (scanning() != null) return Reply.Error("tuner busy")

        if (value == null) {
            return when (item) {
                "status" -> Reply.Value(statusLine(tuner))
                "lockkey" -> Reply.Value(tuner.lockHolder ?: "none")
                "channel" -> Reply.Value(channelField(tuner))
                "vchannel" -> Reply.Value(tuner.channel?.takeIf { !tuner.viaRf }?.guideNumber ?: "none")
                "target" -> Reply.Value(tuner.streamTarget ?: "none")
                "streaminfo" -> Reply.Value(streamInfo(tuner))
                "program" -> Reply.Value(if (tuner.channel == null) "none" else "1")
                "debug" -> Reply.Value(debugText(tuner))
                "plpinfo" -> Reply.Value("")
                "channelmap" -> Reply.Value("us-bcast")
                else -> UNKNOWN
            }
        }
        setsReceived++

        // Sets. A locked tuner takes sets only with its key.
        val held = tuner.lockKey
        if (held != null && key != held && !(item == "lockkey" && value != "none" && held == value.toLongOrNull())) {
            return Reply.Error("resource locked by ${tuner.lockHolder}")
        }
        when (item) {
            "lockkey" -> {
                if (value == "none") {
                    tuner.lockKey = null
                    tuner.lockHolder = null
                    return Reply.Value("none")
                }
                val newKey = value.toLongOrNull() ?: return Reply.Error("invalid lockkey")
                tuner.lockKey = newKey
                tuner.lockHolder = client
                return Reply.Value(client)
            }
            "channel" -> {
                if (value == "none") {
                    tuner.channel = null
                    tuner.viaRf = false
                    return Reply.Value("none")
                }
                val rf = value.removePrefix("auto:").takeIf { value.startsWith("auto:") }?.toIntOrNull()
                    ?: return Reply.Error("invalid channel")
                if (RfChannels.byNumber(rf) == null) return Reply.Error("invalid channel")
                tuner.channel = channels.firstOrNull { it.rf == rf && !it.atsc3 }
                    ?: FakeChannel("", "", rf, SignalPattern.noSignal)
                tuner.viaRf = true
                tuner.tunedAt = clock()
                return Reply.Value(value)
            }
            "vchannel" -> {
                val channel = channels.firstOrNull { it.guideNumber == value && !it.atsc3 }
                    ?: return Reply.Error("invalid virtual channel")
                tuner.channel = channel
                tuner.viaRf = false
                tuner.tunedAt = clock()
                return Reply.Value(value)
            }
            else -> return UNKNOWN
        }
    }

    /** As a FLEX 4K sends it (20261107); only the `eth` line means anything Sightline knows. */
    private fun sysDebug() = "mem: ddr=256 nbk=3 dmk=377 fet=0\nloop: pkt=0\n" +
        (0 until tunerCount).joinToString("") { "t$it: pt=12 cal=82\n" } + "eth: link=$ethernetLink\n"

    private fun channelField(tuner: TunerState): String {
        val channel = tuner.channel ?: return "none"
        if (tuner.viaRf) return "auto:${channel.rf}"
        val hz = RfChannels.byNumber(channel.rf)?.centerHz ?: 0
        return if (channel.atsc3) "atsc3:$hz:1" else "8vsb:$hz"
    }

    private fun statusLine(tuner: TunerState): String {
        val channel = tuner.channel ?: return "ch=none lock=none ss=0 snq=0 seq=0 bps=0 pps=0"
        val elapsed = clock() - tuner.tunedAt
        val signal = channel.pattern.at(elapsed)
        val streaming = tuner.stream != null
        val lockText = when {
            !signal.locked -> "none"
            channel.atsc3 -> "atsc3"
            else -> "8vsb"
        }
        // Like the real unit: the first second after a tune shows a lock with seq and bps still 0.
        val settling = signal.locked && elapsed < 1000
        val seq = if (signal.locked && !settling) signal.seq else 0
        val snq = if (signal.locked) signal.snq else 0
        val bps = if (!signal.locked || settling || channel.atsc3) 0 else 19_392_658L * seq / 100
        val pps = if (streaming && signal.locked) 280 else 0
        return "ch=${channelField(tuner)} lock=$lockText ss=${signal.ss} snq=$snq seq=$seq bps=$bps pps=$pps"
    }

    /** Like the capture: `tun`, `dev`, `ts` and `net` lines, with `te` counting up while the signal is damaged. */
    private fun debugText(tuner: TunerState): String {
        val channel = tuner.channel
            ?: return "tun: ch=none lock=none ss=0 snq=0 seq=0 dbg=0\ndev: bps=0 resync=0 overflow=0\n" +
                "ts:  bps=0 te=0 crc=0\nnet: bps=0 pps=0 err=0 stop=0\n"
        val now = clock()
        val signal = channel.pattern.at(now - tuner.tunedAt)
        if (tuner.countedAt < tuner.tunedAt) {
            tuner.countedAt = tuner.tunedAt
            tuner.transportErrors = 0
            tuner.networkErrors = 0
        }
        val seconds = (now - tuner.countedAt) / 1000
        if (seconds > 0) {
            if (signal.locked) tuner.transportErrors += seconds * (100 - signal.seq) * 3
            tuner.networkErrors += seconds * tuner.networkLossPerSecond
            tuner.countedAt += seconds * 1000
        }
        val ch = channelField(tuner)
        val lock = if (!signal.locked) "none" else ch
        val tsBps = if (signal.locked && !channel.atsc3) 19_392_658L * signal.seq / 100 else 0
        val netBps = if (tuner.stream != null || tuner.streamTarget != null) tsBps else 0
        return "tun: ch=$ch lock=$lock ss=${signal.ss} snq=${if (signal.locked) signal.snq else 0} " +
            "seq=${if (signal.locked) signal.seq else 0} dbg=0\n" +
            "dev: bps=${if (signal.locked) 19_636_224 else 0} resync=0 overflow=0\n" +
            "ts:  bps=$tsBps te=${tuner.transportErrors} crc=${tuner.transportErrors / 4}\n" +
            "net: bps=$netBps pps=${if (netBps > 0) 280 else 0} err=${tuner.networkErrors} stop=0\n"
    }

    private fun streamInfo(tuner: TunerState): String {
        val channel = tuner.channel ?: return "none\n"
        val programs = listOf(channel.guideNumber + " " + channel.guideName) + channel.subchannels
        return programs.withIndex().joinToString("") { (i, p) -> "${i + 1}: $p\n" } + "tsid=0x%04X\n".format(channel.rf)
    }

    private fun clearTuner(tuner: TunerState) {
        tuner.lockKey = null
        tuner.lockHolder = null
        tuner.channel = null
        tuner.viaRf = false
        tuner.streamTarget = null
        tuner.networkLossPerSecond = 0
        tuner.stream?.let { runCatching { it.close() } }
        tuner.stream = null
    }

    // --- HTTP ---

    private fun serveHttp(socket: Socket) {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
        val requestLine = reader.readLine() ?: return
        // Skip the headers, up to the blank line.
        while (true) {
            if (reader.readLine().isNullOrEmpty()) break
        }
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0) ?: return
        val target = parts.getOrNull(1) ?: return
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { '=' in it }
            .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
        val out = socket.getOutputStream()
        val file = FIRMWARE_FILE.matchEntire(path)
        when {
            path == "/discover.json" -> json(out, discoverJson())
            path == "/lineup.json" -> json(out, lineupJson())
            path == "/status.json" -> json(out, statusJson())
            path == "/lineup_status.json" -> json(out, lineupStatusJson())
            path == "/lineup.post" && method == "POST" -> lineupPost(out, query)
            path == "/hdhomerun/${FIRMWARE_NAME}_firmware.bin" ->
                redirect(out, "$baseUrl/hdhomerun/${FIRMWARE_NAME}_firmware_$latestFirmware.bin")
            file != null && file.groupValues[1] == FIRMWARE_NAME && file.groupValues[2] in setOf(latestFirmware, firmwareVersion) ->
                binary(out, firmwareImage(FIRMWARE_NAME, file.groupValues[2]), headOnly = method == "HEAD")
            STREAM_PATH.matches(path) -> stream(socket, out, STREAM_PATH.matchEntire(path)!!)
            else -> respond(out, 404, "Not Found", null)
        }
    }

    private fun stream(socket: Socket, out: OutputStream, match: MatchResult) {
        val which = match.groupValues[1]
        val guide = match.groupValues[2]
        val client = socket.inetAddress.hostAddress
        val tunerIndex: Int
        synchronized(lock) {
            val channel = channels.firstOrNull { it.guideNumber == guide }
                ?: return respond(out, 404, "Not Found", "801 Unknown Channel")
            val candidates = if (which == "auto") tuners.indices.toList() else listOf(which.removePrefix("tuner").toInt())
            val free = candidates.filter { it < tunerCount && tuners[it].channel == null && tuners[it].lockKey == null }
            if (free.isEmpty()) return respond(out, 503, "Service Unavailable", "804 Tuner In Use")
            val capable = free.filter { !channel.atsc3 || it < atsc3Tuners }
            if (capable.isEmpty()) return respond(out, 503, "Service Unavailable", "806 Tune Failed")
            tunerIndex = capable.first()
            tuners[tunerIndex].apply {
                lockKey = -1
                lockHolder = client
                this.channel = channel
                viaRf = false
                tunedAt = clock()
                stream = socket
                streamTarget = "http://$client:${socket.port}"
            }
        }
        try {
            out.write("HTTP/1.1 200 OK\r\nServer: HDHomeRun/1.0\r\nContent-Type: video/mpeg\r\nConnection: close\r\n\r\n".toByteArray())
            val chunk = ByteArray(188 * 7) { if (it % 188 == 0) 0x47 else 0 }
            while (running && !vanished && synchronized(lock) { tuners[tunerIndex].stream === socket }) {
                out.write(chunk)
                out.flush()
                Thread.sleep(20)
            }
        } catch (_: IOException) {
            // The client closed the stream.
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            synchronized(lock) { if (tuners[tunerIndex].stream === socket) clearTuner(tuners[tunerIndex]) }
        }
    }

    private fun lineupPost(out: OutputStream, query: Map<String, String>) {
        synchronized(lock) {
            when (query["scan"]) {
                "start" -> {
                    val source = query["source"] ?: scanSource
                    if (source !in SOURCES) return respond(out, 400, "Bad Request", null)
                    if (scanning() == null) {
                        scansStarted++
                        scanSource = source
                        scanStartedAt = clock()
                    }
                }
                "abort" -> scanStartedAt = null
                else -> return respond(out, 400, "Bad Request", null)
            }
        }
        respond(out, 200, "OK", null)
    }

    /** Milliseconds into the running scan, or null when none runs (clearing a finished one). */
    private fun scanning(): Long? {
        val started = scanStartedAt ?: return null
        val elapsed = clock() - started
        if (elapsed >= scanMs) scanStartedAt = null
        return elapsed.takeIf { it < scanMs }
    }

    /** As captured from a FLEX 4K: idle shows what a scan could look for, scanning shows progress and found. */
    private fun lineupStatusJson() = synchronized(lock) {
        val elapsed = scanning()
        if (elapsed != null) {
            val progress = (elapsed * 100 / scanMs).toInt()
            val programs = channels.size + channels.sumOf { it.subchannels.size }
            """{"ScanInProgress":1,"Progress":$progress,"Found":${programs * progress / 100}}"""
        } else {
            val possible = if (tuners.any { it.channel == null && it.lockKey == null }) 1 else 0
            val sources = SOURCES.joinToString(",", "[", "]") { "\"$it\"" }
            """{"ScanInProgress":0,"ScanPossible":$possible,"Source":"$scanSource","SourceList":$sources}"""
        }
    }

    private fun redirect(out: OutputStream, location: String) {
        out.write("HTTP/1.1 302 Found\r\nLocation: $location\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        out.flush()
    }

    private fun binary(out: OutputStream, body: ByteArray, headOnly: Boolean) {
        val headers = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(headers.toByteArray())
        if (!headOnly) out.write(body)
        out.flush()
    }

    private fun respond(out: OutputStream, code: Int, text: String, hdhrError: String?) {
        val headers = buildString {
            append("HTTP/1.1 $code $text\r\nServer: HDHomeRun/1.0\r\nConnection: close\r\nContent-Length: 0\r\n")
            if (hdhrError != null) append("X-HDHomeRun-Error: $hdhrError\r\n")
            append("\r\n")
        }
        out.write(headers.toByteArray())
        out.flush()
    }

    private fun json(out: OutputStream, body: String) {
        val bytes = body.toByteArray()
        out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun discoverJson() =
        """{"FriendlyName":"Demo tuner","ModelNumber":"HDFX-4K","FirmwareName":"hdhomerun_dvr_atsc3",""" +
            """"FirmwareVersion":"$firmwareVersion","DeviceID":"$deviceIdHex","DeviceAuth":"DEMODEMODEMODEMODEMODEMO",""" +
            """"BaseURL":"$baseUrl","LineupURL":"$baseUrl/lineup.json","TunerCount":$tunerCount""" +
            (upgradeAvailable?.let { ""","UpgradeAvailable":"$it"""" } ?: "") + "}"

    private fun lineupJson() = channels.joinToString(",", "[", "]") { c ->
        val codec = if (c.atsc3) """"VideoCodec":"HEVC","AudioCodec":"AC4"""" else """"VideoCodec":"MPEG2","AudioCodec":"AC3""""
        """{"GuideNumber":"${c.guideNumber}","GuideName":"${c.guideName}",$codec,"HD":1,"URL":"$baseUrl/auto/v${c.guideNumber}"}"""
    }

    private fun statusJson() = synchronized(lock) {
        tuners.withIndex().joinToString(",", "[", "]") { (i, t) ->
            val channel = t.channel
            if (channel == null) """{"Resource":"tuner$i"}"""
            else """{"Resource":"tuner$i","VctNumber":"${channel.guideNumber}","VctName":"${channel.guideName}",""" +
                """"Frequency":${RfChannels.byNumber(channel.rf)?.centerHz ?: 0},"TargetIP":"${t.lockHolder ?: ""}"}"""
        }
    }

    companion object {
        private val UNKNOWN = Reply.Error("unknown getset variable")
        /** `/sys/features` as the FLEX 4K sends it. */
        private const val FEATURES = "channelmap: us-bcast us-cable us-hrc us-irc kr-bcast kr-cable\n" +
            "modulation: atsc3 8vsb qam256 qam64\nauto-modulation: auto auto6t auto6c qam\n"

        private val TUNER_PATH = Regex("/tuner(\\d+)/(\\w+)")
        private val STREAM_PATH = Regex("/(auto|tuner\\d+)/v([\\d.]+)")
        private val FIRMWARE_FILE = Regex("/hdhomerun/([a-z0-9_]+)_firmware_(\\w+)\\.bin")
        private val SOURCES = listOf("Antenna", "Cable")

        /** `/sys/model` and `FirmwareName`, as on a FLEX 4K. */
        const val FIRMWARE_NAME = "hdhomerun_dvr_atsc3"

        private const val IMAGE_MAGIC = "FAKEHDHR-FIRMWARE"

        /**
         * A made-up firmware file for [version]: a text header the fake checks, then filler, over three upload pieces
         * long so uploads are split as on a real unit. Not a real image; a real unit would refuse it.
         */
        fun firmwareImage(name: String = FIRMWARE_NAME, version: String): ByteArray {
            val header = "$IMAGE_MAGIC $name $version\n".toByteArray(Charsets.US_ASCII)
            return header + ByteArray(3_000) { (it % 251).toByte() }
        }

        /** The Demo tuner's channels: one per pattern, names that can't pass for real stations. */
        fun demoLineup() = listOf(
            FakeChannel("2.1", "DEMO Steady", 7, SignalPattern.steady(), subchannels = listOf("2.2 DEMO Two")),
            FakeChannel("4.1", "DEMO Flutter", 9, SignalPattern.flutter()),
            FakeChannel("5.1", "DEMO Wind", 11, SignalPattern.windy()),
            FakeChannel("7.1", "DEMO Fade", 16, SignalPattern.slowFade()),
            FakeChannel("9.1", "DEMO Dropout", 21, SignalPattern.dropouts()),
            FakeChannel("14.1", "DEMO Weak", 27, SignalPattern.steady(ss = 64, snq = 58)),
            FakeChannel("102.1", "DEMO 3.0", 34, SignalPattern.steady(ss = 95, snq = 90), atsc3 = true),
        )
    }
}
