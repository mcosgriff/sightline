package io.cosgriff.sightline.protocol

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.Closeable
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** `GET /discover.json`. The `DeviceAuth` field is a credential and deliberately not read. */
@Serializable
data class DeviceInfo(
    @SerialName("FriendlyName") val friendlyName: String,
    @SerialName("ModelNumber") val modelNumber: String,
    @SerialName("FirmwareName") val firmwareName: String? = null,
    @SerialName("FirmwareVersion") val firmwareVersion: String,
    @SerialName("DeviceID") val deviceId: String,
    @SerialName("TunerCount") val tunerCount: Int,
    @SerialName("BaseURL") val baseUrl: String? = null,
    @SerialName("LineupURL") val lineupUrl: String? = null,
    /**
     * The firmware version on offer when the device, which checks with SiliconDust itself, knows of a newer one.
     * Absent when it's up to date or hasn't checked. Not yet seen in a capture: treat absence as "not reported".
     */
    @SerialName("UpgradeAvailable") val upgradeAvailable: String? = null,
)

/** One `GET /lineup.json` entry. Signal numbers are from the device's last channel scan, not live. */
@Serializable
data class LineupEntry(
    @SerialName("GuideNumber") val guideNumber: String,
    @SerialName("GuideName") val guideName: String,
    @SerialName("VideoCodec") val videoCodec: String? = null,
    @SerialName("AudioCodec") val audioCodec: String? = null,
    @SerialName("HD") val hd: Int? = null,
    @SerialName("Favorite") val favorite: Int? = null,
    @SerialName("URL") val url: String,
)

/** One `GET /status.json` entry. An idle tuner has only [resource]. */
@Serializable
data class TunerUse(
    @SerialName("Resource") val resource: String,
    @SerialName("VctNumber") val vctNumber: String? = null,
    @SerialName("VctName") val vctName: String? = null,
    @SerialName("Frequency") val frequencyHz: Long? = null,
    @SerialName("TargetIP") val targetIp: String? = null,
)

sealed interface HttpResult<out T> {
    data class Ok<T>(val value: T) : HttpResult<T>
    data class Failed(val failure: HttpFailure) : HttpResult<Nothing>
}

sealed interface HttpFailure {
    data object Unreachable : HttpFailure
    data class Status(val code: Int) : HttpFailure
    data object BadBody : HttpFailure
}

/** Why the tuner refused an HTTP stream, from its `X-HDHomeRun-Error` header (captured on a FLEX 4K). */
sealed interface StreamRefusal {
    /** `801 Unknown Channel` (HTTP 404). */
    data object UnknownChannel : StreamRefusal

    /** `804 Tuner In Use` (HTTP 503): another client, or Sightline's own lock key, holds that tuner. */
    data object TunerInUse : StreamRefusal

    /** `806 Tune Failed` (HTTP 503): e.g. ATSC 3.0 asked of a FLEX 4K tuner that only does ATSC 1.0. */
    data object TuneFailed : StreamRefusal

    data class Other(val httpCode: Int, val hdhrCode: Int?) : StreamRefusal

    companion object {
        fun of(httpCode: Int, header: String?): StreamRefusal {
            val code = header?.trim()?.substringBefore(' ')?.toIntOrNull()
            return when (code) {
                801 -> UnknownChannel
                804 -> TunerInUse
                806 -> TuneFailed
                else -> Other(httpCode, code)
            }
        }
    }
}

sealed interface StreamOpen {
    data class Opened(val stream: TunerStream) : StreamOpen
    data class Refused(val refusal: StreamRefusal) : StreamOpen
    data object Unreachable : StreamOpen
}

/**
 * An open HTTP stream holding a tuner. The only way this firmware tunes ATSC 3.0 (ADR-0004). The tuner stays held
 * while the stream is read and is released when it closes, including when the app dies and the socket drops.
 */
class TunerStream internal constructor(
    private val call: Call,
    private val response: Response,
    private val io: CoroutineDispatcher,
) : Closeable {
    @Volatile
    var bytesRead = 0L
        private set

    /** Reads and throws away the stream until it ends or [close] is called. Returns normally either way. */
    suspend fun drain() = withContext(io) {
        val buffer = ByteArray(BUFFER)
        try {
            val input = response.body.byteStream()
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                bytesRead += n
            }
        } catch (_: IOException) {
            // Closed by us, or the tuner stopped sending: both end the stream.
        }
    }

    /**
     * Ends the stream and so frees the tuner. Cancelling the call closes the socket at once, from any thread, and
     * breaks the read [drain] is blocked in. A bare `response.close()` tries to read the rest of the body first,
     * which never ends on a live stream and is network on the main thread when the caller is a ViewModel.
     */
    override fun close() {
        call.cancel()
        response.close()
    }

    private companion object {
        const val BUFFER = 64 * 1024
    }
}

/** The tuner's HTTP endpoints. [client] should have a read timeout of several seconds for streams. */
class HttpApi(
    private val client: OkHttpClient,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun deviceInfo(baseUrl: String): HttpResult<DeviceInfo> =
        getJson(baseUrl.trimEnd('/') + "/discover.json", DeviceInfo.serializer())

    suspend fun lineup(lineupUrl: String): HttpResult<List<LineupEntry>> =
        getJson(lineupUrl, ListSerializer(LineupEntry.serializer()))

    suspend fun status(baseUrl: String): HttpResult<List<TunerUse>> =
        getJson(baseUrl.trimEnd('/') + "/status.json", ListSerializer(TunerUse.serializer()))

    /** Opens the stream at [url] (see [streamUrl]). The caller must [TunerStream.close] an opened stream. */
    suspend fun openStream(url: String): StreamOpen {
        val request = Request.Builder().url(url).build()
        val call = client.newCall(request)
        val response = try {
            call.await()
        } catch (_: IOException) {
            return StreamOpen.Unreachable
        }
        if (response.isSuccessful) return StreamOpen.Opened(TunerStream(call, response, io))
        val refusal = StreamRefusal.of(response.code, response.header("X-HDHomeRun-Error"))
        response.close()
        return StreamOpen.Refused(refusal)
    }

    private suspend fun <T> getJson(url: String, serializer: KSerializer<T>): HttpResult<T> {
        val response = try {
            client.newCall(Request.Builder().url(url).build()).await()
        } catch (_: IOException) {
            return HttpResult.Failed(HttpFailure.Unreachable)
        } catch (_: IllegalArgumentException) {
            return HttpResult.Failed(HttpFailure.Unreachable)
        }
        return response.use {
            if (!it.isSuccessful) return HttpResult.Failed(HttpFailure.Status(it.code))
            val body = try {
                withContext(io) { it.body.string() }
            } catch (_: IOException) {
                return HttpResult.Failed(HttpFailure.Unreachable)
            }
            try {
                HttpResult.Ok(json.decodeFromString(serializer, body))
            } catch (_: SerializationException) {
                HttpResult.Failed(HttpFailure.BadBody)
            } catch (_: IllegalArgumentException) {
                HttpResult.Failed(HttpFailure.BadBody)
            }
        }
    }

    companion object {
        /**
         * The stream URL for one tuner. A lineup entry's URL names `/auto/v<guide>` (any tuner); the same path with
         * `/tuner<N>/` asks for that tuner, which Sightline needs so it only ever takes one it checked is idle.
         * Null when [lineupEntryUrl] isn't a URL of that shape.
         */
        fun streamUrl(lineupEntryUrl: String, tuner: Int): String? {
            val url = lineupEntryUrl.toHttpUrlOrNull() ?: return null
            val segments = url.pathSegments
            if (segments.size != 2 || segments[0] != "auto") return null
            return url.newBuilder().setPathSegment(0, "tuner$tuner").build().toString()
        }
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response) { _, r, _ -> r.close() }
        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
    })
}
