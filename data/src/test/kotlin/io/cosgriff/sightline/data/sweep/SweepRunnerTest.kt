package io.cosgriff.sightline.data.sweep

import com.google.common.truth.Truth.assertThat
import io.cosgriff.sightline.data.device.Channel
import io.cosgriff.sightline.data.device.TunerLeases
import io.cosgriff.sightline.protocol.ControlClient
import io.cosgriff.sightline.protocol.HttpApi
import io.cosgriff.sightline.protocol.TunerSessions
import io.cosgriff.sightline.protocol.fake.FakeHdhr
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
import kotlin.time.Duration.Companion.milliseconds

class SweepRunnerTest {
    private val fake = FakeHdhr()
    private val control = ControlClient(InetAddress.getByName(fake.host), fake.controlPort)
    private val sessions = TunerSessions(fake.deviceIdHex, fake.tunerCount, control, HttpApi(OkHttpClient()))
    private val leases = TunerLeases()
    private val tuned = mutableListOf<String>()
    private val runner = SweepRunner(
        fake.deviceIdHex, sessions, leases,
        sink = { channel, rf, _, _, _ -> tuned += "${channel.guideNumber} rf=$rf" },
        clock = Clock.systemUTC(),
        pollInterval = 40.milliseconds,
        dwell = 300.milliseconds,
        settle = 60.milliseconds,
        busyRetry = 100.milliseconds,
    )

    @After
    fun tearDown() {
        control.close()
        fake.close()
    }

    private fun channel(guide: String, rf: Int? = null) =
        Channel(fake.deviceIdHex, guide, "DEMO", "${fake.baseUrl}/auto/v$guide", rf, null, null, false)

    private val lineup get() = fake.channels.map { channel(it.guideNumber) }

    @Test
    fun `sweeps every channel, atsc 3 included, and leaves every tuner idle`() = runBlocking<Unit> {
        val outcome = withTimeout(20_000) { runner.run(lineup, fake.tunerCount) } as SweepOutcome.Finished
        assertThat(outcome.channels).containsExactlyElementsIn(fake.channels.map { it.guideNumber }).inOrder()
        val perChannel = outcome.readings.groupBy { it.guideNumber }
        fake.channels.forEach { assertThat(perChannel[it.guideNumber].orEmpty().size).isAtLeast(3) }
        assertThat(tuned).contains("2.1 rf=7")
        assertThat(tuned).contains("102.1 rf=34")
        assertThat(leases.held.value).isEmpty()
        // The fake frees a stream-held (ATSC 3.0) tuner once it notices the stream closed.
        withTimeout(2_000) { while ((0 until fake.tunerCount).any { !fake.isIdle(it) }) delay(10) }
        assertThat(runner.progress.value!!.fraction).isEqualTo(1f)
    }

    @Test
    fun `a subchannel on an RF channel already measured is skipped`() = runBlocking<Unit> {
        val first = fake.channels[0]
        val channels = listOf(channel(first.guideNumber), channel("2.2", rf = first.rf), channel(fake.channels[1].guideNumber))
        val outcome = runner.run(channels, fake.tunerCount) as SweepOutcome.Finished
        assertThat(outcome.channels).containsExactly(first.guideNumber, fake.channels[1].guideNumber).inOrder()
    }

    @Test
    fun `atsc 3 with only the 1_0 tuners free is tried and left without readings`() = runBlocking<Unit> {
        fake.takeTuner(0, "192.168.1.90")
        fake.takeTuner(1, "192.168.1.91")
        val outcome = runner.run(listOf(channel("102.1"), channel("2.1")), fake.tunerCount) as SweepOutcome.Finished
        assertThat(outcome.channels).containsExactly("102.1", "2.1").inOrder()
        assertThat(outcome.readings.map { it.guideNumber }.toSet()).containsExactly("2.1")
    }

    @Test
    fun `all busy waits, then carries on when a tuner frees`() = runBlocking<Unit> {
        (0..3).forEach { fake.takeTuner(it, "192.168.1.9$it") }
        val sweep = async(Dispatchers.Default) { runner.run(listOf(channel("2.1")), fake.tunerCount) }
        withTimeout(5_000) { runner.progress.filterNotNull().first { it.phase is SweepPhase.AllBusy } }
        fake.freeTuner(3)
        val outcome = withTimeout(5_000) { sweep.await() } as SweepOutcome.Finished
        assertThat(outcome.readings.map { it.tuner }.toSet()).containsExactly(3)
    }

    @Test
    fun `cancelling mid-dwell releases the tuner`() = runBlocking<Unit> {
        val job = launch(Dispatchers.Default) { runner.run(lineup, fake.tunerCount) }
        withTimeout(5_000) { runner.progress.filterNotNull().first { it.phase is SweepPhase.Measuring } }
        job.cancelAndJoin()
        withTimeout(2_000) { while ((0 until fake.tunerCount).any { !fake.isIdle(it) }) delay(10) }
        assertThat(leases.held.value).isEmpty()
    }
}
