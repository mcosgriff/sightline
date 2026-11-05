package io.cosgriff.sightline.data.live

import com.google.common.truth.Truth.assertThat
import io.cosgriff.sightline.data.device.Channel
import io.cosgriff.sightline.data.device.TunerLeases
import io.cosgriff.sightline.protocol.ControlClient
import io.cosgriff.sightline.protocol.HttpApi
import io.cosgriff.sightline.protocol.LostReason
import io.cosgriff.sightline.protocol.Occupancy
import io.cosgriff.sightline.protocol.TunerSessions
import io.cosgriff.sightline.protocol.fake.FakeHdhr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
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
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.milliseconds

class LiveSessionTest {
    private val fake = FakeHdhr()
    private val control = ControlClient(InetAddress.getByName(fake.host), fake.controlPort)
    private val sessions = TunerSessions(fake.deviceIdHex, fake.tunerCount, control, HttpApi(OkHttpClient()))
    private val leases = TunerLeases()
    private val tuned = mutableListOf<String>()
    private val live = LiveSession(
        fake.deviceIdHex, sessions, leases,
        sink = { channel, rf, _, band, atsc3 -> tuned += "${channel.guideNumber} rf=$rf $band atsc3=$atsc3" },
        clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC),
        pollInterval = 50.milliseconds,
        busyRetry = 100.milliseconds,
    )

    @After
    fun tearDown() {
        control.close()
        fake.close()
    }

    private fun channel(guide: String) =
        Channel(fake.deviceIdHex, guide, "DEMO", "${fake.baseUrl}/auto/v$guide", null, null, null, false)

    private suspend fun <T : LiveState> waitFor(type: Class<T>, extra: (T) -> Boolean = { true }): T = withTimeout(5_000) {
        @Suppress("UNCHECKED_CAST")
        live.state.filterNotNull().first { type.isInstance(it) && extra(it as T) } as T
    }

    @Test
    fun `watches a channel, records what tuning revealed, and releases on cancel`() = runBlocking<Unit> {
        val job = launch(Dispatchers.Default) { live.run(channel("2.1"), fake.tunerCount) }
        val watching = waitFor(LiveState.Watching::class.java) { it.trace.recent.size >= 3 }
        assertThat(watching.tuner).isEqualTo(0)
        assertThat(watching.stream).isFalse()
        assertThat(leases.held.value).containsExactly(fake.deviceIdHex to 0)
        assertThat(tuned).containsExactly("2.1 rf=7 VHF_HI atsc3=false")

        job.cancelAndJoin()
        assertThat(fake.isIdle(0)).isTrue()
        assertThat(leases.held.value).isEmpty()
    }

    @Test
    fun `atsc 3 is watched through a stream`() = runBlocking<Unit> {
        val job = launch(Dispatchers.Default) { live.run(channel("102.1"), fake.tunerCount) }
        val watching = waitFor(LiveState.Watching::class.java) { it.status != null }
        assertThat(watching.stream).isTrue()
        job.cancelAndJoin()
        assertThat(tuned).containsExactly("102.1 rf=34 UHF atsc3=true")
        withTimeout(2_000) { while (!fake.isIdle(0)) kotlinx.coroutines.delay(10) }
    }

    @Test
    fun `all busy says who holds each, then takes a tuner once one is free`() = runBlocking<Unit> {
        (0..3).forEach { fake.takeTuner(it, "192.168.1.9$it") }
        val job = launch(Dispatchers.Default) { live.run(channel("2.1"), fake.tunerCount) }
        val busy = waitFor(LiveState.AllBusy::class.java)
        assertThat(busy.occupancy[2]).isInstanceOf(Occupancy.Busy::class.java)
        assertThat((busy.occupancy[2] as Occupancy.Busy).holder).isEqualTo("192.168.1.92")
        fake.freeTuner(2)
        assertThat(waitFor(LiveState.Watching::class.java).tuner).isEqualTo(2)
        job.cancelAndJoin()
        assertThat(fake.isIdle(2)).isTrue()
    }

    @Test
    fun `a dvr taking the tuner stops live with the reason`() = runBlocking<Unit> {
        val job = launch(Dispatchers.Default) { live.run(channel("2.1"), fake.tunerCount) }
        val watching = waitFor(LiveState.Watching::class.java)
        fake.freeTuner(watching.tuner)
        fake.takeTuner(watching.tuner, "192.168.1.77", fake.channels[1])
        val lost = waitFor(LiveState.Lost::class.java)
        assertThat(lost.reason).isEqualTo(LostReason.TakenBy("192.168.1.77"))
        job.join() // run returns by itself
        assertThat(leases.held.value).isEmpty()
    }

    @Test
    fun `changing channel keeps the same tuner`() = runBlocking<Unit> {
        fake.takeTuner(0, "192.168.1.90")
        var job = launch(Dispatchers.Default) { live.run(channel("2.1"), fake.tunerCount) }
        assertThat(waitFor(LiveState.Watching::class.java).tuner).isEqualTo(1)
        job.cancelAndJoin()
        fake.freeTuner(0)
        job = launch(Dispatchers.Default) { live.run(channel("4.1"), fake.tunerCount) }
        assertThat(waitFor(LiveState.Watching::class.java) { it.channel.guideNumber == "4.1" }.tuner).isEqualTo(1)
        job.cancelAndJoin()
    }

    @Test
    fun `an unknown channel stops`() = runBlocking<Unit> {
        live.run(Channel(fake.deviceIdHex, "999.9", "X", "${fake.baseUrl}/auto/v999.9", null, null, null, false), 4)
        assertThat(live.state.value).isInstanceOf(LiveState.UnknownChannel::class.java)
    }
}
