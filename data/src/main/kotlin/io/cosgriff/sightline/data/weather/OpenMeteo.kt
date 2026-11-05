package io.cosgriff.sightline.data.weather

import io.cosgriff.sightline.analysis.WeatherHour
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/** Where hourly weather comes from. Null when it couldn't be had (offline, an error); the caller may try again. */
fun interface WeatherSource {
    suspend fun hours(latitude: Double, longitude: Double, startMs: Long, endMs: Long, nowMs: Long): List<WeatherHour>?
}

/**
 * Open-Meteo's hourly history (SPEC "After the session"): temperature, wind, gusts and precipitation, metric, in
 * UTC. The only call Sightline makes off the LAN, and only when the user asked for weather. Recent days come from
 * the forecast API, which keeps about three months; older ones from the archive, which runs a few days behind.
 * No key and no account; the request carries home to 2 decimal places and nothing else about the user.
 */
@Singleton
class OpenMeteo @Inject constructor(private val client: OkHttpClient) : WeatherSource {
    override suspend fun hours(latitude: Double, longitude: Double, startMs: Long, endMs: Long, nowMs: Long): List<WeatherHour>? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url(latitude, longitude, startMs, endMs, nowMs)).build()
            try {
                client.newCall(request).execute().use { r -> if (r.isSuccessful) parse(r.body.string()) else null }
            } catch (_: IOException) {
                null
            }
        }

    companion object {
        private const val FORECAST = "https://api.open-meteo.com/v1/forecast"
        private const val ARCHIVE = "https://archive-api.open-meteo.com/v1/archive"
        private const val DAY_MS = 86_400_000L

        /** The forecast API's history reaches back 92 days; leave a margin. */
        private const val FORECAST_KEEPS_MS = 80 * DAY_MS

        private val json = Json { ignoreUnknownKeys = true }

        fun url(latitude: Double, longitude: Double, startMs: Long, endMs: Long, nowMs: Long): HttpUrl {
            fun day(ms: Long) = Instant.ofEpochMilli(ms).atOffset(ZoneOffset.UTC).toLocalDate().toString()
            val base = if (nowMs - startMs < FORECAST_KEEPS_MS) FORECAST else ARCHIVE
            return base.toHttpUrl().newBuilder()
                .addQueryParameter("latitude", "%.2f".format(java.util.Locale.ROOT, latitude))
                .addQueryParameter("longitude", "%.2f".format(java.util.Locale.ROOT, longitude))
                .addQueryParameter("hourly", "temperature_2m,precipitation,wind_speed_10m,wind_gusts_10m")
                .addQueryParameter("start_date", day(startMs))
                .addQueryParameter("end_date", day(endMs))
                .addQueryParameter("timezone", "GMT")
                .addQueryParameter("timeformat", "unixtime")
                .addQueryParameter("wind_speed_unit", "kmh")
                .addQueryParameter("precipitation_unit", "mm")
                .addQueryParameter("temperature_unit", "celsius")
                .build()
        }

        /** The hours in an Open-Meteo reply; null when it isn't one. */
        fun parse(body: String): List<WeatherHour>? {
            val reply = try {
                json.decodeFromString(Reply.serializer(), body)
            } catch (_: SerializationException) {
                return null
            } catch (_: IllegalArgumentException) {
                return null
            }
            val h = reply.hourly
            return h.time.mapIndexed { i, t ->
                WeatherHour(
                    hourMs = t * 1000,
                    tempC = h.temperature.getOrNull(i),
                    windKph = h.wind.getOrNull(i),
                    gustKph = h.gusts.getOrNull(i),
                    precipMm = h.precipitation.getOrNull(i),
                )
            }
        }
    }

    @Serializable
    private class Reply(val hourly: Hourly)

    @Serializable
    private class Hourly(
        val time: List<Long>,
        @SerialName("temperature_2m") val temperature: List<Double?> = emptyList(),
        val precipitation: List<Double?> = emptyList(),
        @SerialName("wind_speed_10m") val wind: List<Double?> = emptyList(),
        @SerialName("wind_gusts_10m") val gusts: List<Double?> = emptyList(),
    )
}
