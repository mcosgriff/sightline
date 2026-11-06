package io.cosgriff.sightline.data.export

import android.app.Application
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import io.cosgriff.sightline.analysis.EventKind
import io.cosgriff.sightline.data.db.EventEntity
import io.cosgriff.sightline.data.db.ReadingEntity
import io.cosgriff.sightline.data.db.SessionEntity
import io.cosgriff.sightline.data.db.SessionKind
import io.cosgriff.sightline.data.db.SightlineDatabase
import io.cosgriff.sightline.data.db.WeatherHourEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** CSV of readings (paged, in time order) and of events with their hour's weather. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SessionExportTest {
    private val app = RuntimeEnvironment.getApplication()
    private val db = Room.inMemoryDatabaseBuilder(app, SightlineDatabase::class.java).allowMainThreadQueries().build()
    private val export = SessionExport(app, db.sessions(), Clock.fixed(Instant.ofEpochMilli(START), ZoneOffset.UTC))
    private val denver = ZoneId.of("America/Denver")

    @After
    fun tearDown() = db.close()

    private suspend fun session(readings: Int): Long {
        val id = db.sessions().insert(
            SessionEntity(deviceId = "D", kind = SessionKind.MONITOR, startedAt = START, endedAt = START + HOUR, channels = listOf("9.1"), tuner = 0, pollIntervalMs = 1_000),
        )
        db.sessions().append(
            (0 until readings).map {
                ReadingEntity(0, id, START + it * 1_000L, "9.1", 0, 80, 88, 100, 19_392_636, 0, "8vsb")
                    .let { r -> if (it == 0) r.copy(transportErrors = 12, crcErrors = 3, resyncs = 0, networkErrors = 0) else r }
            },
            listOf(
                EventEntity(0, id, START + 60_000, START + 64_500, "9.1", EventKind.DIP, 41, 72, 0),
                EventEntity(0, id, START + 2 * HOUR, START + 2 * HOUR + 3_000, null, EventKind.DEVICE_UNREACHABLE, null, null, 3_000),
            ),
        )
        db.sessions().saveWeather(id, listOf(WeatherHourEntity(id, START, 8.5, 20.0, 41.3, 0.2)))
        return id
    }

    @Test
    fun `readings come out in order across pages, with local and utc times`() = runBlocking<Unit> {
        val id = session(12_001)
        val lines = export.readings(id, denver)!!.readLines()
        assertThat(lines.first()).isEqualTo("time,utc_ms,channel,tuner,lock,ss,snq,seq,bps,pps,heading_deg,transport_errors,crc_errors,resyncs,network_errors")
        assertThat(lines).hasSize(12_002)
        assertThat(lines[1]).isEqualTo("2026-11-01T17:00:00-07:00,$START,9.1,0,8vsb,80,88,100,19392636,0,,12,3,0,0")
        assertThat(lines[2]).endsWith(",19392636,0,,,,,")
        assertThat(lines.last()).startsWith("2026-11-01T20:20:00-07:00,${START + 12_000_000L},")
        assertThat(lines.drop(1).map { it.split(',')[1].toLong() }).isInOrder()
    }

    @Test
    fun `events carry their hour's weather, metric, and device-wide ones have no channel`() = runBlocking<Unit> {
        val id = session(0)
        val lines = export.events(id, ZoneOffset.UTC)!!.readLines()
        assertThat(lines[0]).isEqualTo("start,end,start_utc_ms,end_utc_ms,duration_s,kind,channel,min_snq,min_seq,no_lock_s,temp_c,wind_kph,gust_kph,precip_mm")
        assertThat(lines[1]).isEqualTo("2026-11-02T00:01:00Z,2026-11-02T00:01:04.5Z,${START + 60_000},${START + 64_500},4.5,dip,9.1,41,72,0.0,8.5,20.0,41.3,0.2")
        assertThat(lines[2]).endsWith(",3.0,device_unreachable,,,,3.0,,,,")
    }

    @Test
    fun `files are named for the session and a gone session gives nothing`() = runBlocking<Unit> {
        val id = session(1)
        assertThat(export.readings(id, ZoneOffset.UTC)!!.name).isEqualTo("sightline-monitor-20261102-0000-readings.csv")
        assertThat(export.reportFile(id, ZoneOffset.UTC)!!.name).isEqualTo("sightline-monitor-20261102-0000-report.pdf")
        assertThat(export.events(id + 1, ZoneOffset.UTC)).isNull()
    }

    @Test
    fun `fields with commas or quotes are quoted`() {
        assertThat(Csv.field("9.1")).isEqualTo("9.1")
        assertThat(Csv.field("a,b")).isEqualTo("\"a,b\"")
        assertThat(Csv.field("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"")
    }

    private companion object {
        const val HOUR = 3_600_000L
        /** 2026-11-02 00:00 UTC. */
        const val START = 1_793_577_600_000L
    }
}
