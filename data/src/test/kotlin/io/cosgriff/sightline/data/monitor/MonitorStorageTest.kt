package io.cosgriff.sightline.data.monitor

import android.app.Application
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import io.cosgriff.sightline.analysis.EventKind
import io.cosgriff.sightline.analysis.SignalEvent
import io.cosgriff.sightline.analysis.Thresholds
import io.cosgriff.sightline.data.db.ReadingEntity
import io.cosgriff.sightline.data.db.SessionKind
import io.cosgriff.sightline.data.db.SightlineDatabase
import io.cosgriff.sightline.data.device.Channel
import io.cosgriff.sightline.data.device.Device
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Monitor sessions in Room: begun, appended to in batches, ended, and closed when a killed process left them open. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class MonitorStorageTest {
    private val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SightlineDatabase::class.java)
        .allowMainThreadQueries().build()
    private val repository = MonitorRepository(db.sessions())

    @After
    fun tearDown() = db.close()

    private val device = Device("D", "Roof", "HDFX-4K", "1", "http://x", null, 4, "192.168.1.50", 65001, 0)
    private fun channel(guide: String) = Channel("D", guide, "X", "u", null, null, null, false)
    private fun plan(vararg guides: String) =
        MonitorPlan(device, guides.map(::channel), null, 30_000, 3_600_000, 1_000, Thresholds.DEFAULT, fetchWeather = true, notifyDips = false)

    private fun reading(ts: Long) = ReadingEntity(0, 0, ts, "9.1", 1, 80, 85, 100, 19_000_000, 0, "8vsb")

    @Test
    fun `a session is begun, filled in batches and ended`() = runBlocking<Unit> {
        val id = repository.begin(plan("9.1"), 1_000)
        repository.append(listOf(reading(1_000), reading(2_000)), emptyList(), id)
        val dip = SignalEvent(EventKind.DROPOUT, "9.1", 2_500, 9_000, minSnq = null, minSeq = null, noLockMs = 4_000)
        repository.append(listOf(reading(3_000)), listOf(dip, SignalEvent(EventKind.GAP, null, 9_000, 80_000)), id)
        repository.end(id, 90_000, 1)

        val session = db.sessions().get(id)!!
        assertThat(session.kind).isEqualTo(SessionKind.MONITOR)
        assertThat(session.endedAt).isEqualTo(90_000)
        assertThat(session.tuner).isEqualTo(1)
        assertThat(session.fetchWeather).isTrue()
        assertThat(session.dwellMs).isNull() // one channel: held, not rotated
        assertThat(db.sessions().readings(id).map { it.ts }).containsExactly(1_000L, 2_000L, 3_000L).inOrder()
        assertThat(repository.events(id)).containsExactly(dip, SignalEvent(EventKind.GAP, null, 9_000, 80_000)).inOrder()
    }

    @Test
    fun `a watch of every tuner keeps its devices, channels, clients, and where each reading and event was`() = runBlocking<Unit> {
        val other = device.copy(deviceId = "E", friendlyName = "Garage")
        val plan = MonitorPlan(
            device, listOf(channel("9.1")), null, 0, null, 1_000, Thresholds.DEFAULT, fetchWeather = false, notifyDips = false,
            wholeDevices = listOf(device, other),
        )
        val id = repository.begin(plan, 1_000)
        repository.watchedAll(id, listOf("9.1", "4.1"), listOf("192.0.2.20", "192.0.2.21"))
        val dip = SignalEvent(EventKind.DIP, "9.1", 2_000, 4_000, 50, 90, deviceId = "E", tuner = 0)
        val outage = SignalEvent(EventKind.DEVICE_UNREACHABLE, null, 5_000, 8_000, deviceId = "D")
        repository.append(listOf(reading(1_000).copy(deviceId = "D", tuner = 3), reading(1_000).copy(deviceId = "E", tuner = 0)), listOf(dip, outage), id)
        repository.end(id, 9_000, null)

        val session = db.sessions().get(id)!!
        assertThat(session.wholeDevice).isTrue()
        assertThat(session.watched).isTrue()
        assertThat(session.devices).containsExactly("D", "E").inOrder()
        assertThat(session.channels).containsExactly("9.1", "4.1").inOrder()
        assertThat(session.holder).isEqualTo("192.0.2.20,192.0.2.21")
        assertThat(session.tuner).isNull()
        assertThat(db.sessions().readings(id).map { it.deviceId to it.tuner }).containsExactly("D" to 3, "E" to 0)
        assertThat(repository.events(id)).containsExactly(dip, outage).inOrder()
    }

    @Test
    fun `a session a killed process left open is ended at its last reading`() = runBlocking<Unit> {
        val withReadings = repository.begin(plan("9.1", "4.1"), 1_000)
        repository.append(listOf(reading(5_000), reading(6_000)), emptyList(), withReadings)
        val empty = repository.begin(plan("9.1"), 7_000)
        val done = repository.begin(plan("9.1"), 8_000)
        repository.end(done, 20_000, 0)

        repository.closeAbandoned()

        assertThat(db.sessions().get(withReadings)!!.endedAt).isEqualTo(6_000)
        assertThat(db.sessions().get(withReadings)!!.dwellMs).isEqualTo(30_000)
        assertThat(db.sessions().get(empty)!!.endedAt).isEqualTo(7_000)
        assertThat(db.sessions().get(done)!!.endedAt).isEqualTo(20_000)
    }
}
