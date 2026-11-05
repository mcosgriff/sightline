package io.cosgriff.sightline.protocol

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** The answer to a get or set. */
sealed interface ControlResult {
    data class Value(val value: String) : ControlResult

    /** The device answered with an error message (tag 0x05). */
    data class Rejected(val error: ControlError) : ControlResult

    /** No answer: can't connect, timed out, the connection dropped, or the reply was malformed. */
    data class Unreachable(val reason: UnreachableReason) : ControlResult
}

enum class UnreachableReason {
    /** A recent attempt failed and the next one isn't due yet ([Backoff]). */
    BACKING_OFF,
    CONNECT_FAILED,
    TIMED_OUT,
    CONNECTION_LOST,
    MALFORMED_REPLY,
}

/** Error messages as this firmware words them (`docs/protocol-captures/`). */
sealed interface ControlError {
    val message: String

    /** `ERROR: resource locked by 192.168.1.21`: another client holds the tuner (or the set had no lock key). */
    data class Locked(val byAddress: String, override val message: String) : ControlError

    /** `ERROR: unknown getset variable`: no such name, e.g. `/tuner9/status` or `/tunerN/vstatus` on a FLEX 4K. */
    data class UnknownVariable(override val message: String) : ControlError

    /** `ERROR: invalid channel` (also every ATSC 3.0 form on a FLEX 4K). */
    data class InvalidChannel(override val message: String) : ControlError

    /** `ERROR: invalid virtual channel`: not in the lineup, or an ATSC 3.0 channel, which only HTTP can tune. */
    data class InvalidVirtualChannel(override val message: String) : ControlError

    data class Other(override val message: String) : ControlError

    companion object {
        fun of(message: String): ControlError {
            val text = message.removePrefix("ERROR:").trim()
            return when {
                text.startsWith(LOCKED_PREFIX) -> Locked(text.removePrefix(LOCKED_PREFIX).trim(), message)
                text == "unknown getset variable" -> UnknownVariable(message)
                text == "invalid virtual channel" -> InvalidVirtualChannel(message)
                text == "invalid channel" -> InvalidChannel(message)
                else -> Other(message)
            }
        }

        private const val LOCKED_PREFIX = "resource locked by"
    }
}

/**
 * Reconnect delays after consecutive failures: 1, 2, 4, 8, 16 s, then 30 s (SPEC "Polling loop"). A call while
 * the delay runs fails at once with [UnreachableReason.BACKING_OFF] rather than waiting, so a 1 Hz poll keeps
 * its rhythm and counts the failures.
 */
class Backoff(private val timeSource: TimeSource = TimeSource.Monotonic) {
    private var failures = 0
    private var lastFailure: TimeMark? = null

    fun canAttempt(): Boolean {
        val mark = lastFailure ?: return true
        return mark.elapsedNow() >= delayAfter(failures)
    }

    fun failed() {
        failures++
        lastFailure = timeSource.markNow()
    }

    fun succeeded() {
        failures = 0
        lastFailure = null
    }

    companion object {
        fun delayAfter(failures: Int): Duration =
            if (failures <= 0) Duration.ZERO else (1L shl (failures - 1).coerceAtMost(5)).seconds.coerceAtMost(30.seconds)
    }
}

/**
 * One persistent TCP control connection to a device (SPEC "Polling loop" step 1). Calls are serialised; a failed
 * call drops the connection and the next call reconnects, subject to [Backoff]. Thread-safe.
 */
class ControlClient(
    private val address: InetAddress,
    private val port: Int = HDHOMERUN_PORT,
    private val timeout: Duration = 2.seconds,
    timeSource: TimeSource = TimeSource.Monotonic,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : Closeable {
    private val mutex = Mutex()
    private val backoff = Backoff(timeSource)
    private var socket: Socket? = null

    @Volatile
    private var closed = false

    /** This end's address on the connection, which the tuner reports as the lock holder. Null when not connected. */
    @Volatile
    var localAddress: InetAddress? = null
        private set

    suspend fun get(name: String): ControlResult = request(name, value = null, lockKey = null)

    /** A set; [lockKey] is required on a tuner Sightline has claimed. */
    suspend fun set(name: String, value: String, lockKey: Long? = null): ControlResult =
        request(name, value, lockKey)

    private suspend fun request(name: String, value: String?, lockKey: Long?): ControlResult {
        val tlvs = buildList {
            add(Tlv.string(Tag.GETSET_NAME, name))
            if (value != null) add(Tlv.string(Tag.GETSET_VALUE, value))
            if (lockKey != null) add(Tlv.uint32(Tag.LOCK_KEY, lockKey))
        }
        val frame = Packet(PacketType.GETSET_REQUEST, tlvs).encode()
        return mutex.withLock { withContext(io) { exchange(frame) } }
    }

    private fun exchange(frame: ByteArray): ControlResult {
        if (closed) return ControlResult.Unreachable(UnreachableReason.CONNECTION_LOST)
        val s = socket ?: run {
            if (!backoff.canAttempt()) return ControlResult.Unreachable(UnreachableReason.BACKING_OFF)
            // Outside apply {}: in there `port` would be the unconnected Socket's own port, 0.
            val remote = InetSocketAddress(address, port)
            val millis = timeout.inWholeMilliseconds.toInt()
            try {
                Socket().apply {
                    tcpNoDelay = true
                    connect(remote, millis)
                    soTimeout = millis
                }.also {
                    socket = it
                    localAddress = it.localAddress
                }
            } catch (_: IOException) {
                backoff.failed()
                return ControlResult.Unreachable(UnreachableReason.CONNECT_FAILED)
            }
        }
        val reply = try {
            s.getOutputStream().apply { write(frame); flush() }
            readFrame(s.getInputStream())
        } catch (_: java.net.SocketTimeoutException) {
            return drop(UnreachableReason.TIMED_OUT)
        } catch (_: IOException) {
            return drop(UnreachableReason.CONNECTION_LOST)
        } ?: return drop(UnreachableReason.CONNECTION_LOST)

        val packet = (Packet.decode(reply) as? DecodeResult.Ok)?.packet
            ?.takeIf { it.type == PacketType.GETSET_REPLY }
            ?: return drop(UnreachableReason.MALFORMED_REPLY)
        backoff.succeeded()
        packet[Tag.ERROR_MESSAGE]?.let { return ControlResult.Rejected(ControlError.of(it.string())) }
        return ControlResult.Value(packet[Tag.GETSET_VALUE]?.string() ?: "")
    }

    private fun drop(reason: UnreachableReason): ControlResult {
        closeSocket()
        backoff.failed()
        return ControlResult.Unreachable(reason)
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: IOException) {
            // Closing a broken socket can fail; it is gone either way.
        }
        socket = null
        localAddress = null
    }

    override fun close() {
        closed = true
        closeSocket()
    }

    companion object {
        /** Reads one frame, or null at end of stream. */
        internal fun readFrame(input: InputStream): ByteArray? {
            val header = input.readNBytes(Packet.HEADER)
            if (header.size < Packet.HEADER) return null
            val rest = input.readNBytes(Packet.frameSize(header) - Packet.HEADER)
            if (rest.size < Packet.frameSize(header) - Packet.HEADER) return null
            return header + rest
        }
    }
}
