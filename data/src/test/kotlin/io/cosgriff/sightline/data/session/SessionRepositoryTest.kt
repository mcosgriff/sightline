package io.cosgriff.sightline.data.session

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import io.cosgriff.sightline.analysis.EventKind
import io.cosgriff.sightline.analysis.Health
import io.cosgriff.sightline.analysis.WeatherHour
import io.cosgriff.sightline.data.db.EventEntity
import io.cosgriff.sightline.data.db.ReadingEntity
import io.cosgriff.sightline.data.db.SessionEntity
import io.cosgriff.sightline.data.db.SessionKind
import io.cosgriff.sightline.data.db.SightlineDatabase
import io.cosgriff.sightline.data.device.DeviceRepository
import io.cosgriff.sightline.data.device.TunerAccess
import io.cosgriff.sightline.data.device.TunerLeases
import io.cosgriff.sightline.data.settings.AppStateRepository
import io.cosgriff.sightline.data.settings.SettingsRepository
import io.cosgriff.sightline.data.weather.OpenMeteo
import io.cosgriff.sightline.data.weather.WeatherSource
import io.cosgriff.sightline.protocol.Discovery
import io.cosgriff.sightline.protocol.HttpApi
import io.cosgriff.sightline.protocol.PlainDiscoveryEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Optional

/** Summaries for History, analysis for Session detail, weather fetched once, deletion. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SessionRepositoryTest {
    @get:Rule val folder = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SightlineDatabase::class.java)
        .allowMainThreadQueries().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settings by lazy {
        SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("settings.preferences_pb").also { it.delete() } })
    }
    private val state by lazy {
        AppStateRepository(PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("state.preferences_pb").also { it.delete() } })
    }
    private val clock = Clock.fixed(Instant.ofEpochMilli(START + 30 * HOUR), ZoneOffset.UTC)
    private val fetched = mutableListOf<List<Double>>()
    private var reply: List<WeatherHour>? = null
    private val source = WeatherSource { lat, lon, start, end, _ ->
        fetched += listOf(lat, lon, start.toDouble(), end.toDouble())
        reply
    }
    private val repository by lazy {
        val http = HttpApi(OkHttpClient())
        val devices = DeviceRepository(
            db.devices(), db.channels(), Discovery(PlainDiscoveryEnvironment()), http, TunerAccess(http, state), TunerLeases(), clock, Optional.empty(),
        )
        SessionRepository(db.sessions(), devices, settings, source, clock, Optional.empty())
    }

    @After
    fun tearDown() = db.close()

    private suspend fun session(fetchWeather: Boolean = true, endedAt: Long? = START + 2 * HOUR): Long {
        val id = db.sessions().insert(
            SessionEntity(deviceId = "D", kind = SessionKind.MONITOR, startedAt = START, endedAt = endedAt, channels = listOf("9.1"), tuner = 0, pollIntervalMs = 60_000, fetchWeather = fetchWeather),
        )
        // Two hours a minute apart: good, except 10 minutes poor and one reading without lock.
        val readings = (0 until 120).map { m ->
            val poor = m in 30 until 40
            ReadingEntity(0, id, START + m * 60_000L, "9.1", 0, 80, if (poor) 40 else 88, if (poor) 70 else 100, null, null, if (m == 50) "none" else "8vsb")
        }
        db.sessions().append(readings, listOf(EventEntity(0, id, START + 30 * 60_000L, START + 40 * 60_000L, "9.1", EventKind.DIP, 40, 70, 0)))
        return id
    }

    @Test
    fun `finished sessions get a summary for History`() = runBlocking<Unit> {
        val id = session()
        val running = session(endedAt = null)
        repository.summariseMissing()

        val rows = repository.history.first()
        assertThat(rows.map { it.info.id }).containsExactly(id)
        val summary = rows.single().summary!!
        assertThat(summary.readings).isEqualTo(120)
        assertThat(summary.dips).isEqualTo(1)
        assertThat(summary.goodShare).isWithin(1e-9).of(109.0 / 120)
        assertThat(summary.medianSnq).isEqualTo(88)
        assertThat(summary.health).isEqualTo(Health.GOOD)
        assertThat(summary.sparkline).hasSize(48)
        assertThat(summary.sparkline.filterNotNull().min()).isEqualTo(0)
        assertThat(db.sessions().unsummarised()).isEmpty()
        assertThat(running).isNotEqualTo(id)
    }

    @Test
    fun `a session loads with its analysis`() = runBlocking<Unit> {
        val id = session(fetchWeather = false)
        val detail = repository.load(id, ZoneId.of("UTC"))!!
        assertThat(detail.data.readings).hasSize(120)
        assertThat(detail.data.readings[50].sample.locked).isFalse()
        assertThat(detail.stats.dips).isEqualTo(1)
        assertThat(detail.weather).isEqualTo(SessionWeather.NotAsked)
    }

    @Test
    fun `weather without a home can't be fetched`() = runBlocking<Unit> {
        val id = session()
        assertThat(repository.load(id, ZoneId.of("UTC"))!!.weather).isEqualTo(SessionWeather.NoHome)
        assertThat(repository.fetchWeather(id)).isFalse()
        assertThat(fetched).isEmpty()
    }

    @Test
    fun `weather is fetched for home once, kept to the session's hours`() = runBlocking<Unit> {
        settings.setHome(39.7392, -104.9903)
        val id = session()
        reply = (-1..3).map { WeatherHour(START + it * HOUR, 10.0, 5.0, 12.0, 0.0) }
        assertThat(repository.fetchWeather(id)).isTrue()
        assertThat(fetched.single()).containsExactly(39.74, -104.99, START.toDouble(), (START + 2 * HOUR).toDouble()).inOrder()

        val weather = repository.load(id, ZoneId.of("UTC"))!!.weather as SessionWeather.Fetched
        assertThat(weather.hours.map { it.hourMs }).containsExactly(START, START + HOUR, START + 2 * HOUR).inOrder()
        assertThat(weather.demo).isFalse()
        assertThat(repository.fetchWeather(id)).isTrue()
        assertThat(fetched).hasSize(1)
    }

    @Test
    fun `a failed fetch leaves the weather missing`() = runBlocking<Unit> {
        settings.setHome(39.74, -104.99)
        val id = session()
        reply = null
        assertThat(repository.fetchWeather(id)).isFalse()
        assertThat(repository.load(id, ZoneId.of("UTC"))!!.weather).isEqualTo(SessionWeather.Missing)
    }

    @Test
    fun `deleting sessions takes their readings and summaries with them`() = runBlocking<Unit> {
        val a = session()
        val b = session()
        repository.summariseMissing()
        repository.delete(listOf(a))
        assertThat(repository.history.first().map { it.info.id }).containsExactly(b)
        assertThat(db.sessions().readings(a)).isEmpty()
    }

    @Test
    fun `open-meteo replies parse, nulls kept`() {
        val hours = OpenMeteo.parse(
            """{"latitude":39.74,"hourly_units":{},"hourly":{"time":[1793577600,1793581200],"temperature_2m":[4.5,null],""" +
                """"precipitation":[0.0,0.3],"wind_speed_10m":[10.1,12.0],"wind_gusts_10m":[31.7,null]}}""",
        )
        assertThat(hours).containsExactly(
            WeatherHour(1_793_577_600_000, 4.5, 10.1, 31.7, 0.0),
            WeatherHour(1_793_581_200_000, null, 12.0, null, 0.3),
        ).inOrder()
        assertThat(OpenMeteo.parse("""{"error":true,"reason":"bad"}""")).isNull()
        assertThat(OpenMeteo.parse("not json")).isNull()
    }

    @Test
    fun `open-meteo is asked for home's hours in UTC, metric, recent from the forecast and old from the archive`() {
        val recent = OpenMeteo.url(39.7392, -104.9903, START, START + 30 * HOUR, START + 31 * HOUR)
        assertThat(recent.host).isEqualTo("api.open-meteo.com")
        assertThat(recent.queryParameter("latitude")).isEqualTo("39.74")
        assertThat(recent.queryParameter("longitude")).isEqualTo("-104.99")
        assertThat(recent.queryParameter("start_date")).isEqualTo("2026-11-02")
        assertThat(recent.queryParameter("end_date")).isEqualTo("2026-11-03")
        assertThat(recent.queryParameter("wind_speed_unit")).isEqualTo("kmh")
        assertThat(recent.queryParameter("timeformat")).isEqualTo("unixtime")
        val old = OpenMeteo.url(39.74, -104.99, START, START + HOUR, START + 200 * 24 * HOUR)
        assertThat(old.host).isEqualTo("archive-api.open-meteo.com")
    }

    private companion object {
        const val HOUR = 3_600_000L
        const val START = 1_793_577_600_000L
    }
}
