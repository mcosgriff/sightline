package io.cosgriff.sightline.data.monitor

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import io.cosgriff.sightline.analysis.SignalEvent
import io.cosgriff.sightline.analysis.Thresholds
import io.cosgriff.sightline.data.db.ReadingEntity
import io.cosgriff.sightline.data.db.SightlineDatabase
import io.cosgriff.sightline.data.device.Channel
import io.cosgriff.sightline.data.device.DemoDevices
import io.cosgriff.sightline.data.device.Device
import io.cosgriff.sightline.data.device.DeviceRepository
import io.cosgriff.sightline.data.device.StaleLockRecovery
import io.cosgriff.sightline.data.device.TunerAccess
import io.cosgriff.sightline.data.device.TunerLeases
import io.cosgriff.sightline.data.settings.AppStateRepository
import io.cosgriff.sightline.protocol.Discovery
import io.cosgriff.sightline.protocol.HttpApi
import io.cosgriff.sightline.protocol.PlainDiscoveryEnvironment
import io.cosgriff.sightline.protocol.fake.FakeHdhr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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
import java.time.Clock
import java.util.Optional
import kotlin.time.Duration.Companion.milliseconds

/**
 * A two-unit compare whose process dies holding a tuner on each unit: the lock keys it stored by device and tuner
 * let the next start's stale lock-key recovery release both, as the app does (SPEC "Polling loop").
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class CompareRecoveryTest {
    @get:Rule val folder = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SightlineDatabase::class.java)
        .allowMainThreadQueries().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = AppStateRepository(PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("state.preferences_pb").also { it.delete() } })
    private val first = FakeHdhr.demo()
    private val second = FakeHdhr.secondDemo()
    private val http = HttpApi(OkHttpClient())

    private fun device(f: FakeHdhr) =
        Device(f.deviceIdHex, f.friendlyName, "HDFX-4K", f.firmwareVersion, f.baseUrl, "${f.baseUrl}/lineup.json", f.tunerCount, f.host, f.controlPort, 0, demo = true)

    private val a = device(first)
    private val b = device(second)
    private val demos = object : DemoDevices {
        override val devices = listOf(a, b)
    }

    /** Where the stale keys are looked up, as at app start: both units are known. */
    private fun repository(access: TunerAccess) = DeviceRepository(
        db.devices(), db.channels(), Discovery(PlainDiscoveryEnvironment()), http, access, TunerLeases(), Clock.systemUTC(), Optional.of(demos),
    )

    @After
    fun tearDown() {
        db.close()
        first.close()
        second.close()
    }

    private fun channel(f: FakeHdhr) = Channel(f.deviceIdHex, "2.1", "DEMO Steady", "${f.baseUrl}/auto/v2.1", null, null, null, false)

    @Test
    fun `both units' tuners are released at the next start after the process dies holding them`() = runBlocking<Unit> {
        // The process that dies: its own connections, with the lock keys going to DataStore as the app's do.
        val dying = TunerAccess(http, state)
        val runner = MonitorRunner(
            dying.sessions(a), TunerLeases(), sink = { _, _, _, _, _ -> }, log = object : MonitorLog {
                override suspend fun begin(plan: MonitorPlan, startedAt: Long) = 1L
                override suspend fun append(readings: List<ReadingEntity>, events: List<SignalEvent>, sessionId: Long) = Unit
                override suspend fun end(sessionId: Long, endedAt: Long, tuner: Int?) = Unit
            },
            clock = Clock.systemUTC(), lockWait = 300.milliseconds, sessionsOf = dying::sessions,
        )
        val plan = MonitorPlan(
            a, listOf(channel(first), channel(second)), null, 0, null, 200, Thresholds.DEFAULT, fetchWeather = false, notifyDips = false,
            compareUnits = listOf(a, b),
        )
        val job = launch(Dispatchers.Default) { runner.run(plan) }
        val running = withTimeout(10_000) {
            runner.status.filterNotNull().first { s -> s.units.all { it.state is UnitState.Holding } }
        }
        val heldA = (running.units[0].state as UnitState.Holding).tuner
        val heldB = (running.units[1].state as UnitState.Holding).tuner
        assertThat(state.heldLocks().map { it.deviceId to it.tuner }).containsExactly(a.deviceId to heldA, b.deviceId to heldB)

        // Killed: nothing it sends from now on reaches either unit, so its own release never happens.
        first.vanished = true
        second.vanished = true
        job.cancel()
        job.join()
        first.vanished = false
        second.vanished = false
        assertThat(first.isIdle(heldA)).isFalse()
        assertThat(second.isIdle(heldB)).isFalse()
        assertThat(state.heldLocks()).hasSize(2)

        // The next start, with fresh connections.
        val access = TunerAccess(http, state)
        StaleLockRecovery(state, repository(access), access, scope).recover()

        assertThat(first.isIdle(heldA)).isTrue()
        assertThat(second.isIdle(heldB)).isTrue()
        assertThat(state.heldLocks()).isEmpty()
        // The made-up players' tuners were never touched.
        assertThat(first.isIdle(FakeHdhr.DEMO_PLAYER_TUNER)).isFalse()
        assertThat(second.isIdle(FakeHdhr.SECOND_DEMO_PLAYER_TUNER)).isFalse()
    }
}
