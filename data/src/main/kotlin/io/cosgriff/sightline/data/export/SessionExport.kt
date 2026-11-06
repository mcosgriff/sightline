package io.cosgriff.sightline.data.export

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.cosgriff.sightline.analysis.WeatherHour
import io.cosgriff.sightline.data.db.EventEntity
import io.cosgriff.sightline.data.db.ReadingEntity
import io.cosgriff.sightline.data.db.SessionDao
import io.cosgriff.sightline.data.db.SessionEntity
import io.cosgriff.sightline.data.db.WeatherHourEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.Writer
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes a session out for the share sheet (SPEC Screens › Session detail › Export): its readings or its events as
 * CSV, and a place for the report. Files go in the app's cache under `exports/`, shared through the app's
 * FileProvider, and are cleared an hour after they were made.
 *
 * The CSV is for spreadsheets and scripts, so its columns don't follow the units setting: times are local ISO 8601
 * with the offset, plus UTC epoch ms; weather is metric, the unit in each column's name.
 */
@Singleton
class SessionExport @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sessions: SessionDao,
    private val clock: Clock,
) {
    private val dir: File get() = File(context.cacheDir, DIR)

    /** Every reading, in time order; null when the session is gone. */
    suspend fun readings(id: Long, zone: ZoneId): File? = withContext(Dispatchers.IO) {
        val session = sessions.get(id) ?: return@withContext null
        val file = newFile(session, "readings", "csv", zone)
        file.bufferedWriter().use { out ->
            Csv.readingsHeader(out)
            var lastTs = Long.MIN_VALUE
            var lastId = Long.MIN_VALUE
            while (true) {
                val page = sessions.readingsAfter(id, lastTs, lastId, PAGE)
                page.forEach { Csv.reading(out, it, zone) }
                if (page.size < PAGE) break
                lastTs = page.last().ts
                lastId = page.last().id
            }
        }
        file
    }

    /** Every event, with the weather of the hour it started in when the session has weather; null when it's gone. */
    suspend fun events(id: Long, zone: ZoneId): File? = withContext(Dispatchers.IO) {
        val session = sessions.get(id) ?: return@withContext null
        val weather = sessions.weather(id).associateBy { it.hourTs }
        val file = newFile(session, "events", "csv", zone)
        file.bufferedWriter().use { out ->
            Csv.eventsHeader(out)
            sessions.events(id).forEach { e -> Csv.event(out, e, weather[Math.floorDiv(e.startTs, WeatherHour.HOUR_MS) * WeatherHour.HOUR_MS], zone) }
        }
        file
    }

    /** An empty file for [id]'s report, named like its CSVs; null when the session is gone. */
    suspend fun reportFile(id: Long, zone: ZoneId): File? = withContext(Dispatchers.IO) {
        sessions.get(id)?.let { newFile(it, "report", "pdf", zone) }
    }

    private fun newFile(session: SessionEntity, what: String, extension: String, zone: ZoneId): File {
        dir.mkdirs()
        val now = clock.millis()
        dir.listFiles()?.filter { now - it.lastModified() > KEEP_MS }?.forEach { it.delete() }
        val start = FILE_TIME.format(Instant.ofEpochMilli(session.startedAt).atZone(zone))
        return File(dir, "sightline-${session.kind.name.lowercase(Locale.ROOT)}-$start-$what.$extension")
    }

    companion object {
        /** Under the cache directory; `res/xml/file_paths.xml` in `:app` shares this folder and nothing else. */
        const val DIR = "exports"
        private const val PAGE = 5_000
        private const val KEEP_MS = 3_600_000L
        private val FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.ROOT)
    }
}

/** The CSV rows. RFC 4180: comma-separated, CRLF line ends, quoted where a field needs it. */
internal object Csv {
    private val TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun readingsHeader(out: Writer) =
        row(
            out, "time", "utc_ms", "channel", "tuner", "lock", "ss", "snq", "seq", "bps", "pps", "heading_deg",
            "transport_errors", "crc_errors", "resyncs", "network_errors",
        )

    fun reading(out: Writer, r: ReadingEntity, zone: ZoneId) = row(
        out, time(r.ts, zone), r.ts.toString(), r.guideNumber, r.tuner.toString(), r.lock,
        r.ss.toString(), r.snq.toString(), r.seq.toString(), r.bps?.toString(), r.pps?.toString(), r.headingDeg?.let { "%.1f".format(Locale.ROOT, it) },
        r.transportErrors?.toString(), r.crcErrors?.toString(), r.resyncs?.toString(), r.networkErrors?.toString(),
    )

    fun eventsHeader(out: Writer) = row(
        out, "start", "end", "start_utc_ms", "end_utc_ms", "duration_s", "kind", "channel", "min_snq", "min_seq", "no_lock_s",
        "temp_c", "wind_kph", "gust_kph", "precip_mm",
    )

    fun event(out: Writer, e: EventEntity, w: WeatherHourEntity?, zone: ZoneId) = row(
        out, time(e.startTs, zone), time(e.endTs, zone), e.startTs.toString(), e.endTs.toString(), seconds(e.endTs - e.startTs),
        e.kind.name.lowercase(Locale.ROOT), e.guideNumber, e.minSnq?.toString(), e.minSeq?.toString(), seconds(e.noLockMs),
        w?.tempC?.let(::one), w?.windKph?.let(::one), w?.gustKph?.let(::one), w?.precipMm?.let(::one),
    )

    private fun time(ms: Long, zone: ZoneId) = TIME.format(Instant.ofEpochMilli(ms).atZone(zone))

    private fun seconds(ms: Long) = "%.1f".format(Locale.ROOT, ms / 1_000.0)

    private fun one(v: Double) = "%.1f".format(Locale.ROOT, v)

    private fun row(out: Writer, vararg fields: String?) {
        fields.forEachIndexed { i, f ->
            if (i > 0) out.write(",")
            out.write(field(f.orEmpty()))
        }
        out.write("\r\n")
    }

    fun field(f: String): String =
        if (f.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + f.replace("\"", "\"\"") + "\"" else f
}
