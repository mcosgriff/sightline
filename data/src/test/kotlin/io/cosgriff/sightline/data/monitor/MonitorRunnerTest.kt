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
import io.cosgriff.sightline.protocol.LostReason
import io.cosgriff.sightline.protocol.TunerSessions
import io.cosgriff.sightline.protocol.fake.FakeChannel
import io.cosgriff.sightline.protocol.fake.FakeHdhr
import io.cosgriff.sightline.protocol.fake.SignalPattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
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
 * The polling loop against the fake tuner. Time runs [SPEED] times faster for the tuner's patterns and the
 * timestamps, so a minute of monitoring takes 3 s: a poll every 50 ms is a reading a second.
 */
class MonitorRunnerTest {
    private class ScaledClock(private val speed: Long) : Clock() {
        private val t0 = System.nanoTime()
        override fun millis() = 1_000_000L + (System.nanoTime() - t0) / 1_000_000 * speed
        override fun instant(): Instant = Instant.ofEpochMilli(millis())
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?) = this
    }

    private val clock = ScaledClock(SPEED)
    private val fake = FakeHdhr(
        channels = listOf(
            FakeChannel("2.1", "STEADY", 7, SignalPattern.steady()),
            FakeChannel("4.1", "OTHER", 9, SignalPattern.steady(snq = 84)),
            FakeChannel("9.1", "DROPS", 21, SignalPattern.dropouts(everyMs = 20_000, lengthMs = 4_000)),
        ),
        clock = clock::millis,
    )
    private val control = ControlClient(InetAddress.getByName(fake.host), fake.controlPort)
    private val sessions = TunerSessions(fake.deviceIdHex, fake.tunerCount, control, HttpApi(OkHttpClient()))
    private val leases = TunerLeases()
    private val device = Device(fake.deviceIdHex, "Fake", "HDFX-4K", "1", fake.baseUrl, null, fake.tunerCount, fake.host, fake.controlPort, 0)

    private class MemoryLog : MonitorLog {
        val readings = mutableListOf<ReadingEntity>()
        val events = mutableListOf<SignalEvent>()
        var batches = 0
        var ended: Pair<Long, Int?>? = null

        override suspend fun begin(plan: MonitorPlan, startedAt: Long) = 7L

        override suspend fun append(readings: List<ReadingEntity>, events: List<SignalEvent>, sessionId: Long) {
            assertThat(sessionId).isEqualTo(7)
            this.readings += readings
            this.events += events
            batches++
        }

        override suspend fun end(sessionId: Long, endedAt: Long, tuner: Int?) {
            ended = endedAt to tuner
        }
    }

    private val log = MemoryLog()
    private val runner = MonitorRunner(
        sessions, leases, sink = { _, _, _, _, _ -> }, log = log, clock = clock,
        lockWait = 150.milliseconds, lockPoll = 20.milliseconds, flushEvery = 200.milliseconds, busyRetry = 200.milliseconds,
    )

    @After
    fun tearDown() {
        control.close()
        fake.close()
    }

    private fun channel(guide: String) = Channel(fake.deviceIdHex, guide, "X", "${fake.baseUrl}/auto/v$guide", null, null, null, false)

    private fun plan(vararg guides: String, durationS: Long? = 60, tuner: Int? = null, dwellS: Long = 10) = MonitorPlan(
        device = device,
        channels = guides.map(::channel),
        tuner = tuner,
        dwellMs = dwellS * 1000 / SPEED,
        durationMs = durationS?.let { it * 1000 },
        pollIntervalMs = 1000 / SPEED,
        thresholds = Thresholds.DEFAULT,
        fetchWeather = false,
        notifyDips = false,
    )

    private suspend fun waitFor(test: (MonitorStatus) -> Boolean): MonitorStatus =
        withTimeout(10_000) { runner.status.filterNotNull().first(test) }

    @Test
    fun `a minute of the dropout channel records its dropouts, ends on time and releases the tuner`() = runBlocking<Unit> {
        val end = withTimeout(15_000) { runner.run(plan("9.1")) }
        assertThat(end).isEqualTo(MonitorEnd.TIME_UP)
        // Readings a second apart for the minute, written in more than one batch.
        assertThat(log.readings.size).isIn(45..65)
        assertThat(log.batches).isGreaterThan(1)
        assertThat(log.readings.map { it.guideNumber }.toSet()).containsExactly("9.1")
        // Dropouts at 16-20 s, 36-40 s and 56-60 s.
        val dropouts = log.events.filter { it.kind == EventKind.DROPOUT }
        assertThat(dropouts.size).isIn(2..3)
        assertThat(dropouts.first().noLockMs).isIn(2_000L..6_000L)
        assertThat(log.ended?.second).isEqualTo(0)
        assertThat(fake.isIdle(0)).isTrue()
        assertThat(leases.held.value).isEmpty()
        assertThat(runner.status.value?.ended).isEqualTo(MonitorEnd.TIME_UP)
    }

    @Test
    fun `stopping ends the session, writes what is buffered and releases the tuner`() = runBlocking<Unit> {
        val run = async(Dispatchers.Default) { runner.run(plan("2.1", durationS = null)) }
        waitFor { it.readings >= 5 }
        run.cancelAndJoin()
        assertThat(log.ended).isNotNull()
        assertThat(log.readings.size).isAtLeast(5)
        assertThat(fake.isIdle(0)).isTrue()
        assertThat(runner.status.value?.ended).isEqualTo(MonitorEnd.STOPPED)
    }

    @Test
    fun `a tuner taken by a DVR is an event, and another idle tuner carries on`() = runBlocking<Unit> {
        val run = launch(Dispatchers.Default) { runner.run(plan("2.1", durationS = null)) }
        waitFor { it.readings >= 3 }
        fake.takeTuner(0, "192.168.1.9", fake.channels[1])
        val moved = waitFor { (it.phase as? MonitorPhase.Watching)?.tuner == 1 && it.events.any { e -> e.kind == EventKind.TUNER_LOST } }
        assertThat(moved.events.single { it.kind == EventKind.TUNER_LOST }.guideNumber).isEqualTo("2.1")
        run.cancelAndJoin()
        // Ours released; the DVR's left alone.
        assertThat(fake.isIdle(1)).isTrue()
        assertThat(fake.isIdle(0)).isFalse()
        assertThat(log.ended?.second).isNull() // two tuners used
    }

    @Test
    fun `a picked tuner that is taken pauses the monitor until it is free again`() = runBlocking<Unit> {
        val run = launch(Dispatchers.Default) { runner.run(plan("2.1", durationS = null, tuner = 2)) }
        waitFor { it.readings >= 3 }
        fake.takeTuner(2, "192.168.1.9", fake.channels[1])
        val paused = waitFor { it.phase is MonitorPhase.Paused }
        assertThat((paused.phase as MonitorPhase.Paused).reason).isEqualTo(LostReason.TakenBy("192.168.1.9"))
        fake.freeTuner(2)
        waitFor { (it.phase as? MonitorPhase.Watching)?.tuner == 2 && it.events.any { e -> e.kind == EventKind.TUNER_LOST } }
        run.cancelAndJoin()
        assertThat(fake.isIdle(2)).isTrue()
    }

    @Test
    fun `with every tuner busy it waits, never taking one`() = runBlocking<Unit> {
        repeat(fake.tunerCount) { fake.takeTuner(it, "192.168.1.9") }
        val run = launch(Dispatchers.Default) { runner.run(plan("2.1", durationS = null)) }
        waitFor { it.phase is MonitorPhase.AllBusy }
        delay(300)
        assertThat((0 until fake.tunerCount).none { fake.isIdle(it) }).isTrue()
        fake.freeTuner(3)
        waitFor { (it.phase as? MonitorPhase.Watching)?.tuner == 3 && it.readings > 0 }
        run.cancelAndJoin()
    }

    @Test
    fun `rotate mode moves through the channels`() = runBlocking<Unit> {
        val end = withTimeout(15_000) { runner.run(plan("2.1", "4.1", durationS = 50, dwellS = 10)) }
        assertThat(end).isEqualTo(MonitorEnd.TIME_UP)
        assertThat(log.readings.map { it.guideNumber }.toSet()).containsExactly("2.1", "4.1")
        // Changing channel is not a gap or a dip.
        assertThat(log.events).isEmpty()
        assertThat(fake.isIdle(0)).isTrue()
    }

    @Test
    fun `a device that stops answering is its own event`() = runBlocking<Unit> {
        val run = launch(Dispatchers.Default) { runner.run(plan("2.1", durationS = null)) }
        waitFor { it.readings >= 3 }
        fake.vanished = true
        waitFor { s -> s.open.any { it.kind == EventKind.DEVICE_UNREACHABLE } }
        fake.vanished = false
        val back = waitFor { s -> s.events.any { it.kind == EventKind.DEVICE_UNREACHABLE } }
        assertThat(back.events.none { it.kind == EventKind.GAP }).isTrue()
        run.cancelAndJoin()
    }

    private companion object {
        const val SPEED = 20L
    }
}
