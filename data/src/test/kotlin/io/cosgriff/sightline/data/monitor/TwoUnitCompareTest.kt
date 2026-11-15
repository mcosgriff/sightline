package io.cosgriff.sightline.data.monitor

import com.google.common.truth.Truth.assertThat
import io.cosgriff.sightline.analysis.Diagnosis
import io.cosgriff.sightline.analysis.EventKind
import io.cosgriff.sightline.analysis.Finding
import io.cosgriff.sightline.analysis.LoggedReading
import io.cosgriff.sightline.analysis.SessionData
import io.cosgriff.sightline.analysis.SignalEvent
import io.cosgriff.sightline.analysis.SignalSample
import io.cosgriff.sightline.analysis.Strength
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
import kotlinx.coroutines.delay
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
 * A two-unit compare against the two Demo tuners, as debug builds start them: the made-up player on tuner 3 of the
 * first and tuner 0 of the second, so each has three idle tuners. Time runs [SPEED] times faster, as in
 * [MonitorRunnerTest]; with a poll every 10 s an 80-minute session takes under 10 s.
 */
class TwoUnitCompareTest {
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

    private class MemoryLog : MonitorLog {
        val readings = mutableListOf<ReadingEntity>()
        val events = mutableListOf<SignalEvent>()
        var began = false
        var ended: Pair<Long, Int?>? = null

        override suspend fun begin(plan: MonitorPlan, startedAt: Long): Long {
            began = true
            return 11L
        }

        override suspend fun append(readings: List<ReadingEntity>, events: List<SignalEvent>, sessionId: Long) {
            this.readings += readings
            this.events += events
        }

        override suspend fun end(sessionId: Long, endedAt: Long, tuner: Int?) {
            ended = endedAt to tuner
        }
    }

    private val log = MemoryLog()
    private val leases = TunerLeases()
    private val runner = MonitorRunner(
        sessionsOf(first), leases, sink = { _, _, _, _, _ -> }, log = log, clock = clock,
        lockWait = (3_000 / SPEED).milliseconds, lockPoll = 2.milliseconds, flushEvery = 100.milliseconds, busyRetry = 100.milliseconds,
        sessionsOf = sessionsOf(second).let { s -> { d: Device -> if (d.deviceId == b.deviceId) s else error("unknown device") } },
    )

    @After
    fun tearDown() {
        controls.forEach { it.close() }
        first.close()
        second.close()
    }

    private fun channel(fake: FakeHdhr, guide: String) =
        fake.channels.single { it.guideNumber == guide }.let { Channel(fake.deviceIdHex, it.guideNumber, it.guideName, "${fake.baseUrl}/auto/v${it.guideNumber}", null, null, null, false) }

    private fun plan(guide: String = "2.1", durationMin: Long? = 80, pollMs: Long = 10_000) = MonitorPlan(
        device = a,
        channels = listOf(channel(first, guide), channel(second, guide)),
        tuner = null,
        dwellMs = 0,
        durationMs = durationMin?.let { it * 60_000 },
        pollIntervalMs = pollMs / SPEED,
        thresholds = Thresholds.DEFAULT,
        fetchWeather = false,
        notifyDips = false,
        compareUnits = listOf(a, b),
    )

    private suspend fun waitFor(test: (MonitorStatus) -> Boolean): MonitorStatus =
        withTimeout(10_000) { runner.status.filterNotNull().first(test) }

    private fun MonitorStatus.unit(d: Device) = units.single { it.deviceId == d.deviceId }

    private fun MonitorStatus.bothHolding() = units.all { it.state is UnitState.Holding && it.latest != null }

    /** The session as Session detail analyses it: readings keyed by device, on the scaled clock's 10 s polls. */
    private fun data(pollMs: Long = 10_000): SessionData {
        val readings = log.readings.sortedBy { it.ts }.map {
            LoggedReading(it.ts, it.guideNumber, it.tuner, SignalSample(it.lock != "none", it.ss, it.snq, it.seq), deviceId = it.deviceId!!)
        }
        return SessionData(readings.first().atMs, readings.last().atMs, pollMs, readings, log.events.sortedBy { it.startMs })
    }

    /** Every tuner but the made-up player's is free again on both units, and no lease is left. */
    private fun assertAllReleased() {
        for (t in 0 until 4) {
            if (t != FakeHdhr.DEMO_PLAYER_TUNER) assertThat(first.isIdle(t)).isTrue()
            if (t != FakeHdhr.SECOND_DEMO_PLAYER_TUNER) assertThat(second.isIdle(t)).isTrue()
        }
        assertThat(leases.held.value).isEmpty()
    }

    @Test
    fun `a fault on one unit gives the One unit finding, and both tuners are released at the end`() = runBlocking<Unit> {
        // Every 10 minutes the second unit alone loses lock for 30 s, as a failing coax branch would.
        second.fault = UnitFault.recurring(everyMs = 600_000, lengthMs = 30_000)
        val end = withTimeout(30_000) { runner.run(plan()) }
        assertThat(end).isEqualTo(MonitorEnd.TIME_UP)

        val dips = log.events.filter { it.isDip }
        assertThat(dips.size).isAtLeast(5)
        assertThat(dips.all { it.deviceId == b.deviceId && it.tuner != null }).isTrue()
        val findings = Diagnosis.run(data(), Thresholds.DEFAULT)
        val oneUnit = findings.filterIsInstance<Finding.OneUnit>().single()
        assertThat(oneUnit.deviceId).isEqualTo(b.deviceId)
        assertThat(oneUnit.otherDeviceId).isEqualTo(a.deviceId)
        assertThat(oneUnit.otherDips).isEqualTo(0)
        assertThat(oneUnit.strength).isEqualTo(Strength.LIKELY)
        assertThat(findings.filterIsInstance<Finding.TwoUnitCommonPath>()).isEmpty()

        // The same channel on one tuner of each, from the top down, a tuner kept idle on each the whole time.
        assertThat(log.readings.map { it.deviceId to it.guideNumber }.toSet()).containsExactly(a.deviceId to "2.1", b.deviceId to "2.1")
        assertThat(log.readings.filter { it.deviceId == a.deviceId }.map { it.tuner }.toSet()).containsExactly(2)
        assertThat(log.readings.filter { it.deviceId == b.deviceId }.map { it.tuner }.toSet()).containsExactly(3)
        assertThat(log.ended).isEqualTo(log.ended!!.first to null)
        assertAllReleased()
    }

    @Test
    fun `a fault on both units at once gives Common path, not One unit`() = runBlocking<Unit> {
        first.fault = UnitFault.recurring(everyMs = 600_000, lengthMs = 30_000)
        second.fault = UnitFault.recurring(everyMs = 600_000, lengthMs = 30_000)
        withTimeout(30_000) { runner.run(plan()) }

        val findings = Diagnosis.run(data(), Thresholds.DEFAULT)
        val common = findings.filterIsInstance<Finding.TwoUnitCommonPath>().single()
        assertThat(common.totalDips).isAtLeast(10)
        assertThat(common.sharedDips).isEqualTo(common.totalDips)
        assertThat(common.strength).isEqualTo(Strength.LIKELY)
        assertThat(findings.filterIsInstance<Finding.OneUnit>()).isEmpty()
        assertAllReleased()
    }

    @Test
    fun `stopping releases both tuners`() = runBlocking<Unit> {
        val job = launch(Dispatchers.Default) { runner.run(plan(durationMin = null)) }
        val running = waitFor { it.bothHolding() && it.readings >= 6 }
        val heldA = (running.unit(a).state as UnitState.Holding).tuner
        val heldB = (running.unit(b).state as UnitState.Holding).tuner
        assertThat(first.lockKeyOf(heldA)).isNotNull()
        assertThat(second.lockKeyOf(heldB)).isNotNull()
        assertThat(leases.held.value).containsExactly(a.deviceId to heldA, b.deviceId to heldB)
        assertThat(running.phase).isEqualTo(MonitorPhase.Comparing)

        job.cancel()
        job.join()
        assertAllReleased()
        assertThat(runner.status.value?.ended).isEqualTo(MonitorEnd.STOPPED)
        assertThat(log.ended).isNotNull()
    }

    @Test
    fun `ATSC 3_0 is held by a stream on tuner 0 or 1 of each unit, with no lock key`() = runBlocking<Unit> {
        val job = launch(Dispatchers.Default) { runner.run(plan(guide = "102.1", durationMin = null)) }
        val running = waitFor { it.bothHolding() }
        for (u in running.units) {
            val holding = u.state as UnitState.Holding
            assertThat(holding.stream).isTrue()
            assertThat(holding.tuner).isAnyOf(0, 1)
        }
        // The fake marks a stream's (and the player's) tuner with key -1; no lock key of Sightline's is set anywhere.
        assertThat((0 until 4).mapNotNull { first.lockKeyOf(it) }.all { it == -1L }).isTrue()
        assertThat((0 until 4).mapNotNull { second.lockKeyOf(it) }.all { it == -1L }).isTrue()
        job.cancel()
        job.join()
        // A closed stream frees its tuner once the device notices, a moment later.
        withTimeout(5_000) { while (!first.isIdle(0) || !first.isIdle(1) || !second.isIdle(1)) delay(10) }
        assertAllReleased()
    }

    @Test
    fun `a unit with no idle tuner to spare refuses the session before anything is claimed or saved`() = runBlocking<Unit> {
        // The second unit's player is on tuner 0; two more recordings leave it one idle tuner, the DVR's.
        second.takeTuner(1, "192.0.2.30")
        second.takeTuner(2, "192.0.2.31")
        val end = withTimeout(10_000) { runner.run(plan()) }
        assertThat(end).isEqualTo(MonitorEnd.NO_SPARE_TUNER)
        val status = runner.status.value!!
        assertThat(status.ended).isEqualTo(MonitorEnd.NO_SPARE_TUNER)
        assertThat(status.unit(b).state).isInstanceOf(UnitState.NoSpare::class.java)
        assertThat(status.unit(a).state).isEqualTo(UnitState.Claiming)
        assertThat(log.began).isFalse()
        assertThat(log.ended).isNull()
        assertThat(first.setsReceived + second.setsReceived).isEqualTo(0)
    }

    @Test
    fun `a unit gives its tuner back when a recording takes its last idle one, and the other carries on`() = runBlocking<Unit> {
        val job = launch(Dispatchers.Default) { runner.run(plan(durationMin = null)) }
        val running = waitFor { it.bothHolding() }
        val heldB = (running.unit(b).state as UnitState.Holding).tuner
        val spare = (0 until 4).filter { it != heldB && second.isIdle(it) }
        assertThat(spare).isNotEmpty()
        // Recordings start on every idle tuner of the second unit.
        spare.forEachIndexed { i, t -> second.takeTuner(t, "192.0.2.4$i") }
        val waiting = waitFor { it.unit(b).state is UnitState.NoSpare }
        assertThat(second.isIdle(heldB)).isTrue()
        assertThat(leases.held.value.map { it.first }).containsExactly(a.deviceId)
        val before = waiting.readings
        waitFor { it.readings >= before + 5 && it.unit(a).state is UnitState.Holding } // the first unit is still read

        // A recording ends: the second unit has a spare again and claims a tuner.
        second.freeTuner(spare.first())
        waitFor { it.unit(b).state is UnitState.Holding }
        job.cancel()
        job.join()
        assertThat(log.events.none { it.kind == EventKind.GAP }).isTrue()
        assertThat(second.isIdle(heldB)).isTrue()
        assertThat(second.isIdle(spare.first())).isTrue()
        assertThat(leases.held.value).isEmpty()
    }

    private companion object {
        const val SPEED = 500L
    }
}
