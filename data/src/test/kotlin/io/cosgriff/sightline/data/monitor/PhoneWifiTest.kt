package io.cosgriff.sightline.data.monitor

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.cosgriff.sightline.analysis.EventKind
import io.cosgriff.sightline.analysis.Finding
import io.cosgriff.sightline.analysis.SignalEvent
import io.cosgriff.sightline.analysis.Strength
import io.cosgriff.sightline.analysis.Thresholds
import io.cosgriff.sightline.analysis.WifiSample
import io.cosgriff.sightline.data.db.ReadingEntity
import io.cosgriff.sightline.data.db.SightlineDatabase
import io.cosgriff.sightline.data.device.Channel
import io.cosgriff.sightline.data.device.Device
import io.cosgriff.sightline.data.device.DeviceRepository
import io.cosgriff.sightline.data.device.TunerAccess
import io.cosgriff.sightline.data.device.TunerLeases
import io.cosgriff.sightline.data.session.SessionRepository
import io.cosgriff.sightline.data.settings.AppStateRepository
import io.cosgriff.sightline.data.settings.SettingsRepository
import io.cosgriff.sightline.data.weather.WeatherSource
import io.cosgriff.sightline.protocol.ControlClient
import io.cosgriff.sightline.protocol.Discovery
import io.cosgriff.sightline.protocol.HttpApi
import io.cosgriff.sightline.protocol.PlainDiscoveryEnvironment
import io.cosgriff.sightline.protocol.TunerSessions
import io.cosgriff.sightline.protocol.fake.FakeHdhr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Optional
import kotlin.time.Duration.Companion.milliseconds

/**
 * The phone's own Wi-Fi with a monitor session (SPEC "Phone Wi-Fi"), against the two Demo tuners as debug builds
 * start them, with the Wi-Fi scripted. Time runs [SPEED] times faster, as in [MonitorRunnerTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PhoneWifiTest {
    @get:Rule val folder = TemporaryFolder()

    private class ScaledClock(private val speed: Long) : Clock() {
        private val t0 = System.nanoTime()
        override fun millis() = START + (System.nanoTime() - t0) / 1_000_000 * speed
        override fun instant(): Instant = Instant.ofEpochMilli(millis())
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?) = this
    }

    private val clock = ScaledClock(SPEED)
    private val first = FakeHdhr.demo(clock = clock::millis)
    private val second = FakeHdhr.secondDemo(clock = clock::millis)
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val controls = mutableListOf<ControlClient>()
    private val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SightlineDatabase::class.java)
        .allowMainThreadQueries().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        controls.forEach { it.close() }
        first.close()
        second.close()
        db.close()
    }

    private fun device(fake: FakeHdhr) =
        Device(fake.deviceIdHex, fake.friendlyName, "HDFX-4K", "1", fake.baseUrl, null, fake.tunerCount, fake.host, fake.controlPort, 0)

    private val a = device(first)
    private val b = device(second)

    private fun sessionsOf(fake: FakeHdhr): TunerSessions {
        val control = ControlClient(loopback, fake.controlPort).also { controls += it }
        return TunerSessions(fake.deviceIdHex, fake.tunerCount, control, HttpApi(OkHttpClient()))
    }

    private fun lineup(fake: FakeHdhr) =
        fake.channels.map { Channel(fake.deviceIdHex, it.guideNumber, it.guideName, "${fake.baseUrl}/auto/v${it.guideNumber}", null, null, null, false) }

    private fun channel(fake: FakeHdhr, guide: String) = lineup(fake).single { it.guideNumber == guide }

    private fun runner(log: MonitorLog, wifi: PhoneWifi): MonitorRunner {
        val sessionsB = sessionsOf(second)
        return MonitorRunner(
            sessionsOf(first), TunerLeases(), sink = { _, _, _, _, _ -> }, log = log, clock = clock,
            lockWait = (3_000 / SPEED).milliseconds, lockPoll = 5.milliseconds, flushEvery = 100.milliseconds, busyRetry = 100.milliseconds,
            sessionsOf = { d -> if (d.deviceId == b.deviceId) sessionsB else error("unknown device") },
            wifi = wifi,
        )
    }

    private fun plan(
        channels: List<Channel>,
        durationS: Long? = 30,
        tuner: Int? = null,
        watch: Boolean = false,
        wholeDevices: List<Device> = emptyList(),
        compareUnits: List<Device> = emptyList(),
    ) = MonitorPlan(
        device = a,
        channels = channels,
        tuner = tuner,
        dwellMs = 10_000 / SPEED,
        durationMs = durationS?.let { it * 1_000 },
        pollIntervalMs = 1_000 / SPEED,
        thresholds = Thresholds.DEFAULT,
        fetchWeather = false,
        notifyDips = false,
        watch = watch,
        wholeDevices = wholeDevices,
        compareUnits = compareUnits,
    )

    private fun on(rssi: Int = -55, bssid: String? = AP_1) = WifiState(true, rssi, 866, 5_180, bssid)

    /**
     * Milestone 8's last "done when" (#27): the Demo tuner held on its Steady channel, the phone drops off Wi-Fi and
     * the tuner stops answering with it, then both come back. Session detail, from Room, says Phone Wi-Fi, not Not RF.
     */
    @Test
    fun `a scripted Wi-Fi drop during a Demo session gives the Phone Wi-Fi finding instead of Not RF`() = runBlocking<Unit> {
        val wifi = MutableStateFlow(on())
        val runner = runner(MonitorRepository(db.sessions()), { wifi })
        suspend fun waitFor(test: (MonitorStatus) -> Boolean) = withTimeout(15_000) { runner.status.filterNotNull().first(test) }

        val run = launch(Dispatchers.Default) { runner.run(plan(listOf(channel(first, "2.1")), durationS = null)) }
        waitFor { it.readings >= 5 }
        wifi.value = on(rssi = -71)
        delay(100)
        // The phone leaves Wi-Fi, and so the tuner is out of reach.
        wifi.value = WifiState.OFF
        first.vanished = true
        waitFor { s -> s.open.any { it.kind == EventKind.DEVICE_UNREACHABLE } }
        first.vanished = false
        wifi.value = on(rssi = -60)
        val back = waitFor { s -> s.events.any { it.kind == EventKind.DEVICE_UNREACHABLE } }
        waitFor { it.readings >= back.readings + 3 }
        run.cancelAndJoin()

        val detail = sessions().load(back.sessionId, ZoneOffset.UTC)!!
        assertThat(detail.data.wifi.map { it.connected }).containsExactly(true, true, false, true).inOrder()
        assertThat(detail.data.wifi.first().bssid).isEqualTo(AP_1)
        val wifiFinding = detail.findings.filterIsInstance<Finding.PhoneWifi>().single()
        assertThat(wifiFinding.offWifi).isEqualTo(1)
        assertThat(wifiFinding.strength).isEqualTo(Strength.LIKELY)
        assertThat(detail.findings.filterIsInstance<Finding.NotRf>()).isEmpty()
        // The tuner's lock is given back as on every exit.
        assertThat(first.isIdle(0)).isTrue()
    }

    private class MemoryLog : MonitorLog {
        val wifi = mutableListOf<WifiSample>()
        val readings = mutableListOf<ReadingEntity>()

        override suspend fun begin(plan: MonitorPlan, startedAt: Long) = 3L

        override suspend fun append(readings: List<ReadingEntity>, events: List<SignalEvent>, sessionId: Long) {
            this.readings += readings
        }

        override suspend fun append(readings: List<ReadingEntity>, events: List<SignalEvent>, wifi: List<WifiSample>, sessionId: Long) {
            this.readings += readings
            this.wifi += wifi
        }

        override suspend fun end(sessionId: Long, endedAt: Long, tuner: Int?) = Unit
    }

    @Test
    fun `every mode records the phone's Wi-Fi, each change once`() = runBlocking<Unit> {
        val modes = mapOf(
            "hold" to plan(listOf(channel(first, "2.1"))),
            "rotate" to plan(listOf(channel(first, "2.1"), channel(first, "7.1"))),
            "watch a tuner" to plan(lineup(first), tuner = FakeHdhr.DEMO_PLAYER_TUNER, watch = true),
            "watch every tuner" to plan(lineup(first) + lineup(second), wholeDevices = listOf(a, b)),
            "compare two units" to plan(listOf(channel(first, "2.1"), channel(second, "2.1")), compareUnits = listOf(a, b)),
        )
        for ((mode, plan) in modes) {
            val log = MemoryLog()
            // The Wi-Fi now, the same again (not a change), then a roam to the second access point.
            val wifi = PhoneWifi {
                flow {
                    emit(on())
                    emit(on())
                    delay(300)
                    emit(on(rssi = -62, bssid = AP_2))
                    awaitCancellation()
                }
            }
            withTimeout(15_000) { runner(log, wifi).run(plan) }
            assertWithMessage(mode).that(log.readings).isNotEmpty()
            assertWithMessage(mode).that(log.wifi.map { it.bssid }).containsExactly(AP_1, AP_2).inOrder()
            assertWithMessage(mode).that(log.wifi.map { it.atMs }).isInOrder()
        }
    }

    @Test
    fun `a session without Wi-Fi stores no samples, and old sessions read as having none`() = runBlocking<Unit> {
        val repository = MonitorRepository(db.sessions())
        val end = withTimeout(15_000) { runner(repository, PhoneWifi.NONE).run(plan(listOf(channel(first, "2.1")), durationS = 10)) }
        assertThat(end).isEqualTo(MonitorEnd.TIME_UP)
        val id = db.sessions().readings(1).first().sessionId
        assertThat(db.sessions().wifi(id)).isEmpty()
        assertThat(sessions().load(id, ZoneOffset.UTC)!!.data.wifi).isEmpty()
    }

    private fun sessions(): SessionRepository {
        val settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("settings.preferences_pb").also { it.delete() } })
        val state = AppStateRepository(PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("state.preferences_pb").also { it.delete() } })
        val http = HttpApi(OkHttpClient())
        val devices = DeviceRepository(
            db.devices(), db.channels(), Discovery(PlainDiscoveryEnvironment()), http, TunerAccess(http, state), TunerLeases(), clock, Optional.empty(),
        )
        return SessionRepository(db.sessions(), devices, settings, WeatherSource { _, _, _, _, _ -> null }, clock, Optional.empty())
    }

    private companion object {
        const val SPEED = 20L
        const val START = 1_793_577_600_000L
        const val AP_1 = "aa:bb:cc:00:00:01"
        const val AP_2 = "aa:bb:cc:00:00:02"
    }
}
