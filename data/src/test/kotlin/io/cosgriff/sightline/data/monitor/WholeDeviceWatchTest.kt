package io.cosgriff.sightline.data.monitor

import com.google.common.truth.Truth.assertThat
import io.cosgriff.sightline.analysis.EventKind
import io.cosgriff.sightline.analysis.SignalEvent
import io.cosgriff.sightline.analysis.Thresholds
import io.cosgriff.sightline.data.db.ReadingEntity
import io.cosgriff.sightline.data.device.Channel
import io.cosgriff.sightline.data.device.Device
import io.cosgriff.sightline.data.device.TunerLeases
import io.cosgriff.sightline.protocol.ControlClient
import io.cosgriff.sightline.protocol.HttpApi
import io.cosgriff.sightline.protocol.TunerSessions
import io.cosgriff.sightline.protocol.fake.FakeHdhr
import io.cosgriff.sightline.protocol.fake.UnitFault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Test
import java.net.InetAddress
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.milliseconds

/**
 * A whole-device watch against the two Demo tuners, as debug builds start them: the made-up player on tuner 3 of
 * the first and tuner 0 of the second. Time runs [SPEED] times faster, as in [MonitorRunnerTest].
 */
class WholeDeviceWatchTest {
    private class ScaledClock(private val speed: Long) : Clock() {
        private val t0 = System.nanoTime()
        override fun millis() = 1_000_000L + (System.nanoTime() - t0) / 1_000_000 * speed
        override fun instant(): Instant = Instant.ofEpochMilli(millis())
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?) = this
    }

    private val clock = ScaledClock(SPEED)
    private val first = FakeHdhr.demo(clock = clock::millis)
    private val second = FakeHdhr.secondDemo(clock = clock::millis)
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val controls = mutableListOf<ControlClient>()

    private fun device(fake: FakeHdhr) =
        Device(fake.deviceIdHex, fake.friendlyName, "HDFX-4K", "1", fake.baseUrl, null, fake.tunerCount, fake.host, fake.controlPort, 0)

    private fun sessionsOf(fake: FakeHdhr): TunerSessions {
        val control = ControlClient(loopback, fake.controlPort).also { controls += it }
        return TunerSessions(fake.deviceIdHex, fake.tunerCount, control, HttpApi(OkHttpClient()))
    }

    private val a = device(first)
    private val b = device(second)
    private val sessionsA = sessionsOf(first)
    private val sessionsB = sessionsOf(second)

    private class MemoryLog : MonitorLog {
        val readings = mutableListOf<ReadingEntity>()
        val events = mutableListOf<SignalEvent>()
        var ended: Pair<Long, Int?>? = null
        var watched: Pair<List<String>, List<String>>? = null

        override suspend fun begin(plan: MonitorPlan, startedAt: Long) = 9L

        override suspend fun append(readings: List<ReadingEntity>, events: List<SignalEvent>, sessionId: Long) {
            this.readings += readings
            this.events += events
        }

        override suspend fun end(sessionId: Long, endedAt: Long, tuner: Int?) {
            ended = endedAt to tuner
        }

        override suspend fun watchedAll(sessionId: Long, channels: List<String>, holders: List<String>) {
            watched = channels to holders
        }
    }

    private val log = MemoryLog()
    private val learned = mutableListOf<String>()
    private val runner = MonitorRunner(
        sessionsA, TunerLeases(), sink = { c, _, _, _, _ -> synchronized(learned) { learned += "${c.deviceId} ${c.guideNumber}" } },
        log = log, clock = clock, flushEvery = 200.milliseconds,
        sessionsOf = { d -> if (d.deviceId == b.deviceId) sessionsB else error("unknown device") },
    )

    @After
    fun tearDown() {
        controls.forEach { it.close() }
        first.close()
        second.close()
    }

    private fun lineup(fake: FakeHdhr) = fake.channels.map { Channel(fake.deviceIdHex, it.guideNumber, it.guideName, "${fake.baseUrl}/auto/v${it.guideNumber}", null, null, null, false) }

    private fun plan(vararg devices: Device, durationS: Long? = 60) = MonitorPlan(
        device = devices.first(),
        channels = devices.flatMap { d -> lineup(if (d == a) first else second) },
        tuner = null,
        dwellMs = 0,
        durationMs = durationS?.let { it * 1_000 },
        pollIntervalMs = 1_000 / SPEED,
        thresholds = Thresholds.DEFAULT,
        fetchWeather = false,
        notifyDips = false,
        wholeDevices = devices.toList(),
    )

    private suspend fun waitFor(test: (MonitorStatus) -> Boolean): MonitorStatus =
        withTimeout(10_000) { runner.status.filterNotNull().first(test) }

    private fun MonitorStatus.use(device: Device, tuner: Int) = tuners.single { it.deviceId == device.deviceId && it.tuner == tuner }.use

    private fun FakeHdhr.channel(guide: String) = channels.single { it.guideNumber == guide }

    @Test
    fun `both units are watched, every player's channel change is logged on its tuner, idle tuners record nothing and nothing is set`() = runBlocking<Unit> {
        val job = launch(Dispatchers.Default) { runner.run(plan(a, b, durationS = 120)) }
        waitFor { s -> s.use(a, 3) is WatchedUse.InUse && s.use(b, 0) is WatchedUse.InUse && s.readings >= 10 }

        // The player moves on the first unit and starts a second recording there; the second unit's player retunes.
        first.freeTuner(3)
        first.takeTuner(3, FakeHdhr.DEMO_PLAYER, first.channel("2.1"))
        first.takeTuner(1, "192.0.2.21", first.channel("7.1"))
        second.freeTuner(0)
        second.takeTuner(0, FakeHdhr.DEMO_PLAYER, second.channel("9.1"))
        val moved = waitFor { s ->
            (s.use(a, 3) as? WatchedUse.InUse)?.let { it.channel.guideNumber == "2.1" && it.latest != null } == true &&
                (s.use(a, 1) as? WatchedUse.InUse)?.latest != null &&
                (s.use(b, 0) as? WatchedUse.InUse)?.let { it.channel.guideNumber == "9.1" && it.latest != null } == true
        }
        assertThat((moved.use(a, 1) as WatchedUse.InUse).holder).isEqualTo("192.0.2.21")
        assertThat(moved.use(a, 0)).isEqualTo(WatchedUse.Idle)
        assertThat(moved.use(b, 3)).isEqualTo(WatchedUse.Idle)
        assertThat(moved.phase).isEqualTo(MonitorPhase.WatchingAll())
        job.join()

        val byTuner = log.readings.groupBy { it.deviceId to it.tuner }.mapValues { (_, rs) -> rs.map { it.guideNumber }.distinct() }
        assertThat(byTuner).containsExactly(
            a.deviceId to 3, listOf("5.1", "2.1"),
            a.deviceId to 1, listOf("7.1"),
            b.deviceId to 0, listOf("4.1", "9.1"),
        )
        // Never a set on any tuner of either unit.
        assertThat(first.setsReceived).isEqualTo(0)
        assertThat(second.setsReceived).isEqualTo(0)
        assertThat(log.events.all { it.deviceId != null }).isTrue()
        // Idle tuners have no events of their own.
        assertThat(log.events.filter { it.deviceId == a.deviceId }.mapNotNull { it.tuner }.toSet() - setOf(1, 3)).isEmpty()
        assertThat(log.events.filter { it.deviceId == b.deviceId }.mapNotNull { it.tuner }.toSet() - setOf(0)).isEmpty()
        // In the order first seen: the two players' channels at the start, then the three changes (in any order).
        val (channels, holders) = log.watched!!
        assertThat(channels.take(2)).containsExactly("5.1", "4.1").inOrder()
        assertThat(channels).containsExactly("5.1", "4.1", "2.1", "7.1", "9.1")
        assertThat(holders).containsExactly(FakeHdhr.DEMO_PLAYER, "192.0.2.21").inOrder()
        assertThat(log.ended?.second).isNull()
        assertThat(synchronized(learned) { learned.toSet() }).containsAtLeast("${a.deviceId} 5.1", "${b.deviceId} 4.1")
        assertThat(runner.status.value?.ended).isEqualTo(MonitorEnd.TIME_UP)
    }

    @Test
    fun `one device watched alone reads only its tuners`() = runBlocking<Unit> {
        withTimeout(15_000) { runner.run(plan(a, durationS = 15)) }
        assertThat(log.readings).isNotEmpty()
        assertThat(log.readings.map { it.deviceId to it.tuner }.toSet()).containsExactly(a.deviceId to 3)
        assertThat(second.setsReceived).isEqualTo(0)
        assertThat(first.setsReceived).isEqualTo(0)
    }

    @Test
    fun `a fault on one unit dips its tuner only`() = runBlocking<Unit> {
        // Both players on steady channels, so only the fault can make a dip.
        first.freeTuner(3)
        first.takeTuner(3, FakeHdhr.DEMO_PLAYER, first.channel("2.1"))
        second.freeTuner(0)
        second.takeTuner(0, FakeHdhr.DEMO_PLAYER, second.channel("2.1"))
        val from = clock.millis() + 20_000
        second.fault = UnitFault.dropout(from, from + 8_000)
        withTimeout(15_000) { runner.run(plan(a, b, durationS = 50)) }
        val dropouts = log.events.filter { it.kind == EventKind.DROPOUT }
        assertThat(dropouts).isNotEmpty()
        assertThat(dropouts.all { it.deviceId == b.deviceId && it.tuner == 0 && it.guideNumber == "2.1" }).isTrue()
    }

    @Test
    fun `a unit that stops answering is one event for that unit, and the other carries on`() = runBlocking<Unit> {
        val job = launch(Dispatchers.Default) { runner.run(plan(a, b, durationS = null)) }
        waitFor { it.readings >= 6 }
        second.vanished = true
        val gone = waitFor { s -> s.open.any { it.kind == EventKind.DEVICE_UNREACHABLE } }
        assertThat((gone.phase as MonitorPhase.WatchingAll).unreachable).containsExactly(b.deviceId)
        assertThat(gone.use(b, 0)).isEqualTo(WatchedUse.NoAnswer)
        val before = gone.readings
        waitFor { it.readings >= before + 5 } // the first unit is still read
        second.vanished = false
        val back = waitFor { s -> s.events.any { it.kind == EventKind.DEVICE_UNREACHABLE } }
        val outage = back.events.single { it.kind == EventKind.DEVICE_UNREACHABLE }
        assertThat(outage.deviceId).isEqualTo(b.deviceId)
        assertThat(outage.tuner).isNull()
        job.cancel()
        job.join()
        assertThat(log.events.none { it.kind == EventKind.GAP }).isTrue()
    }

    private companion object {
        const val SPEED = 20L
    }
}
