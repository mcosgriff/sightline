package io.cosgriff.sightline.protocol

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Firmware versions as the devices write them: a build date, `20261107`, sometimes with a suffix (`20120704beta1`). */
object FirmwareVersions {
    private val DATE = Regex("^(\\d{8})")

    /** The build date as a number, or null when [version] doesn't start with one. */
    fun date(version: String): Int? = DATE.find(version.trim())?.groupValues?.get(1)?.toIntOrNull()

    /** True when [candidate] is a later build than [installed]. False when either can't be read: never guess newer. */
    fun isNewer(candidate: String, installed: String): Boolean {
        val c = date(candidate) ?: return false
        val i = date(installed) ?: return false
        return c > i
    }
}

/** What SiliconDust says is the newest firmware for one firmware name. */
sealed interface FirmwareLookup {
    data class Latest(val version: String) : FirmwareLookup

    /** SiliconDust has no file for that firmware name. */
    data object UnknownFirmware : FirmwareLookup

    /** The firmware name isn't one Sightline will put in a URL (letters, digits and `_` only). */
    data object BadName : FirmwareLookup

    data class Failed(val failure: HttpFailure) : FirmwareLookup
}

sealed interface FirmwareDownload {
    class Ok(val version: String, val image: ByteArray) : FirmwareDownload

    data object NotFound : FirmwareDownload

    /** Bigger than [SiliconDustFirmware.MAX_IMAGE]: not a firmware image. */
    data object TooLarge : FirmwareDownload

    data object BadName : FirmwareDownload

    data class Failed(val failure: HttpFailure) : FirmwareDownload
}

/**
 * Firmware files from SiliconDust's download server, the only host besides the tuner and Open-Meteo that Sightline
 * calls (ADR-0005), and only when the user asks. Files are `<FirmwareName>_firmware_<version>.bin`; the unversioned
 * `<FirmwareName>_firmware.bin` redirects to the newest, so a HEAD request with redirects off names the newest
 * version without downloading it. Nothing about the device or the phone is sent: the firmware name is public.
 *
 * [baseUrl] is SiliconDust's in release builds; the Demo tuner and tests serve the same paths themselves.
 */
class SiliconDustFirmware(
    client: OkHttpClient,
    private val baseUrl: String = DEFAULT_BASE,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val download = client
    private val noRedirects = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    suspend fun latest(firmwareName: String): FirmwareLookup {
        if (!NAME.matches(firmwareName)) return FirmwareLookup.BadName
        val url = fileUrl(firmwareName, version = null) ?: return FirmwareLookup.BadName
        val response = try {
            noRedirects.newCall(Request.Builder().url(url).head().build()).await()
        } catch (_: IOException) {
            return FirmwareLookup.Failed(HttpFailure.Unreachable)
        }
        return response.use {
            when {
                it.code == 404 -> FirmwareLookup.UnknownFirmware
                it.isRedirect -> {
                    val file = it.header("Location")?.substringBefore('?')?.substringAfterLast('/')
                    val version = file?.let { f -> versionIn(f, firmwareName) }
                    if (version != null) FirmwareLookup.Latest(version) else FirmwareLookup.Failed(HttpFailure.BadBody)
                }
                else -> FirmwareLookup.Failed(HttpFailure.Status(it.code))
            }
        }
    }

    /** Downloads one firmware file into memory (a FLEX 4K's is about 2.2 MB). [onProgress] gets bytes and total. */
    suspend fun download(
        firmwareName: String,
        version: String,
        onProgress: (read: Long, total: Long?) -> Unit = { _, _ -> },
    ): FirmwareDownload {
        if (!NAME.matches(firmwareName) || !VERSION.matches(version)) return FirmwareDownload.BadName
        val url = fileUrl(firmwareName, version) ?: return FirmwareDownload.BadName
        val response = try {
            download.newCall(Request.Builder().url(url).build()).await()
        } catch (_: IOException) {
            return FirmwareDownload.Failed(HttpFailure.Unreachable)
        }
        return response.use {
            if (it.code == 404) return@use FirmwareDownload.NotFound
            if (!it.isSuccessful) return@use FirmwareDownload.Failed(HttpFailure.Status(it.code))
            val total = it.body.contentLength().takeIf { n -> n >= 0 }
            if (total != null && total > MAX_IMAGE) return@use FirmwareDownload.TooLarge
            withContext(io) {
                try {
                    val out = ByteArrayOutputStream(total?.toInt() ?: (4 shl 20))
                    val buffer = ByteArray(64 * 1024)
                    val input = it.body.byteStream()
                    var read = 0L
                    while (true) {
                        ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        read += n
                        if (read > MAX_IMAGE) return@withContext FirmwareDownload.TooLarge
                        out.write(buffer, 0, n)
                        onProgress(read, total)
                    }
                    if (read == 0L || (total != null && read != total)) FirmwareDownload.Failed(HttpFailure.BadBody)
                    else FirmwareDownload.Ok(version, out.toByteArray())
                } catch (_: IOException) {
                    FirmwareDownload.Failed(HttpFailure.Unreachable)
                }
            }
        }
    }

    private fun fileUrl(firmwareName: String, version: String?): String? {
        val file = if (version == null) "${firmwareName}_firmware.bin" else "${firmwareName}_firmware_$version.bin"
        return (baseUrl.trimEnd('/') + "/hdhomerun/" + file).toHttpUrlOrNull()?.toString()
    }

    companion object {
        const val DEFAULT_BASE = "https://download.silicondust.com"

        /** Far above any HDHomeRun image (a FLEX 4K's is 2.2 MB), so a wrong file can't fill the phone's memory. */
        const val MAX_IMAGE = 32L shl 20

        private val NAME = Regex("[a-z0-9_]{1,64}")
        private val VERSION = Regex("\\d{8}[a-z0-9]{0,16}")

        /** The version in a file name `<firmwareName>_firmware_<version>.bin`, or null for any other name. */
        fun versionIn(fileName: String, firmwareName: String): String? {
            val prefix = "${firmwareName}_firmware_"
            if (!fileName.startsWith(prefix) || !fileName.endsWith(".bin")) return null
            return fileName.removePrefix(prefix).removeSuffix(".bin").takeIf { VERSION.matches(it) }
        }
    }
}
