package io.cosgriff.sightline.protocol

import com.google.common.truth.Truth.assertThat
import io.cosgriff.sightline.protocol.fake.FakeChannel
import io.cosgriff.sightline.protocol.fake.FakeHdhr
import io.cosgriff.sightline.protocol.fake.Signal
import io.cosgriff.sightline.protocol.fake.SignalPattern
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class TunerSessionsTest {
    private var now = 1_000_000L
    private val fake = FakeHdhr(
        channels = FakeHdhr.demoLineup() + FakeChannel("50.1", "DEMO Gone", 30, SignalPattern { Signal.NONE }),
        clock = { now },
    )
    private val control = ControlClient(InetAddress.getByName(fake.host), fake.controlPort)
    private val http = HttpApi(OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build())
    private val keys = RecordingLockKeys()
    private val sessions = TunerSessions(fake.deviceIdHex, fake.tunerCount, control, http, keys, Random(7))

    @After
    fun tearDown() {
        control.close()
        fake.close()
    }

    private fun vchannel(guide: String) = TuneTarget.Virtual(guide, "${fake.baseUrl}/auto/v$guide")

    @Test
    fun `claims the first idle tuner with a lock key and releases it`() = runBlocking<Unit> {
        coroutineScope {
            val claimed = (sessions.claim(this, vchannel("2.1")) as ClaimResult.Claimed).tuner
            assertThat(claimed.tuner).isEqualTo(0)
            val key = (claimed.hold as Hold.LockKey).key
            assertThat(fake.lockKeyOf(0)).isEqualTo(key)
            assertThat(keys.held).containsExactly(fake.deviceIdHex to 0, key)
            assertThat(claimed.channel).isEqualTo(TunedChannel.Frequency(Modulation.VSB8, 177_000_000))

            now += 2_000
            val reading = (claimed.poll() as Poll.Reading).status
            assertThat(reading.isLocked).isTrue()
            assertThat(reading.seq).isEqualTo(100)

            claimed.release()
            assertThat(fake.isIdle(0)).isTrue()
            assertThat(keys.held).isEmpty()
            assertThat(claimed.poll()).isEqualTo(Poll.Lost(LostReason.Released))
            claimed.release() // twice is fine
        }
    }

    @Test
    fun `never takes a busy tuner`() = runBlocking<Unit> {
        fake.takeTuner(0, "192.168.1.50")
        fake.takeTuner(1, "192.168.1.51")
        coroutineScope {
            val claimed = (sessions.claim(this, vchannel("4.1")) as ClaimResult.Claimed).tuner
            assertThat(claimed.tuner).isEqualTo(2)
            claimed.release()
        }
        assertThat(fake.lockKeyOf(0)).isEqualTo(-1L)
        assertThat(fake.lockKeyOf(1)).isEqualTo(-1L)
    }

    @Test
    fun `all busy says who holds each`() = runBlocking<Unit> {
        (0..3).forEach { fake.takeTuner(it, "192.168.1.5$it") }
        val result = coroutineScope { sessions.claim(this, vchannel("2.1")) } as ClaimResult.NoneFree
        assertThat(result.occupancy[0]).isEqualTo(
            Occupancy.Busy("192.168.1.50", TunedChannel.Frequency(Modulation.VSB8, 177_000_000)),
        )
        assertThat(result.occupancy.keys).containsExactly(0, 1, 2, 3)
    }

    @Test
    fun `atsc3 goes over http on a tuner that can do it`() = runBlocking<Unit> {
        coroutineScope {
            val claimed = (sessions.claim(this, vchannel("102.1")) as ClaimResult.Claimed).tuner
            assertThat(claimed.tuner).isEqualTo(0)
            assertThat(claimed.hold).isInstanceOf(Hold.Stream::class.java)
            assertThat(claimed.channel).isEqualTo(TunedChannel.Frequency(Modulation.ATSC3, 593_000_000, "1"))
            assertThat(keys.held).isEmpty() // the stream holds it; nothing stale to recover
            now += 2_000
            val status = (claimed.poll() as Poll.Reading).status
            assertThat(status.lock).isEqualTo(Lock.Locked(Modulation.ATSC3, "atsc3"))
            assertThat(status.bps).isEqualTo(0)
            claimed.release()
            withTimeout(2_000) { while (!fake.isIdle(0)) kotlinx.coroutines.delay(10) }
        }
    }

    @Test
    fun `atsc3 skips tuners that only do atsc 1`() = runBlocking<Unit> {
        fake.takeTuner(0, "192.168.1.50")
        fake.takeTuner(1, "192.168.1.51")
        val result = coroutineScope { sessions.claim(this, vchannel("102.1")) }
        // Tuners 2 and 3 are idle but answer 806; none was taken, and both are left as they were.
        assertThat(result).isInstanceOf(ClaimResult.NoneFree::class.java)
        assertThat((result as ClaimResult.NoneFree).occupancy[2]).isEqualTo(Occupancy.Idle)
        assertThat(fake.isIdle(2)).isTrue()
        assertThat(fake.isIdle(3)).isTrue()
    }

    @Test
    fun `a dvr taking the tuner is reported lost`() = runBlocking<Unit> {
        coroutineScope {
            val claimed = (sessions.claim(this, vchannel("2.1")) as ClaimResult.Claimed).tuner
            fake.freeTuner(claimed.tuner)
            fake.takeTuner(claimed.tuner, "192.168.1.60", fake.channels[1])
            assertThat(claimed.poll()).isEqualTo(Poll.Lost(LostReason.TakenBy("192.168.1.60")))
            claimed.release()
            // Release can't undo another client's lock.
            assertThat(fake.lockKeyOf(claimed.tuner)).isEqualTo(-1L)
        }
    }

    @Test
    fun `an unknown channel is refused, tuner left idle`() = runBlocking<Unit> {
        val result = coroutineScope { sessions.claim(this, TuneTarget.Virtual("999.9", null)) }
        assertThat(result).isEqualTo(ClaimResult.UnknownChannel)
        assertThat(fake.isIdle(0)).isTrue()
        assertThat(keys.held).isEmpty()
        assertThat(coroutineScope { sessions.claim(this, vchannel("999.9")) }).isEqualTo(ClaimResult.UnknownChannel)
    }

    @Test
    fun `tunes by rf channel`() = runBlocking<Unit> {
        coroutineScope {
            val claimed = (sessions.claim(this, TuneTarget.Rf(9)) as ClaimResult.Claimed).tuner
            assertThat(claimed.channel).isEqualTo(TunedChannel.Auto(9))
            now += 2_000
            assertThat((claimed.poll() as Poll.Reading).status.channel).isEqualTo(TunedChannel.Auto(9))
            claimed.release()
        }
    }

    @Test
    fun `a dropout is a reading with no lock, not a lost tuner`() = runBlocking<Unit> {
        coroutineScope {
            val claimed = (sessions.claim(this, vchannel("50.1")) as ClaimResult.Claimed).tuner
            val status = (claimed.poll() as Poll.Reading).status
            assertThat(status.isLocked).isFalse()
            claimed.release()
        }
    }

    @Test
    fun `withTuner releases when the block throws or is cancelled`() = runBlocking<Unit> {
        val thrown = runCatching {
            coroutineScope { sessions.withTuner(this, vchannel("2.1")) { error("boom") } }
        }
        assertThat(thrown.exceptionOrNull()).hasMessageThat().isEqualTo("boom")
        assertThat(fake.isIdle(0)).isTrue()

        val started = CompletableDeferred<Unit>()
        val job = async {
            coroutineScope { sessions.withTuner(this, vchannel("2.1")) { started.complete(Unit); awaitCancellation() } }
        }
        started.await()
        assertThat(fake.isIdle(0)).isFalse()
        job.cancel()
        runCatching { job.await() }.exceptionOrNull().let { assertThat(it).isInstanceOf(CancellationException::class.java) }
        assertThat(fake.isIdle(0)).isTrue()
        assertThat(keys.held).isEmpty()
    }

    @Test
    fun `a stale lock key from an earlier process is released`() = runBlocking<Unit> {
        val old = ControlClient(InetAddress.getByName(fake.host), fake.controlPort)
        old.set("/tuner3/lockkey", "4242")
        old.set("/tuner3/vchannel", "2.1", 4242)
        old.close()
        assertThat(fake.isIdle(3)).isFalse()
        assertThat(sessions.releaseStale(3, 4242)).isTrue()
        assertThat(fake.isIdle(3)).isTrue()
    }

    @Test
    fun `a stale key never frees a tuner someone else holds`() = runBlocking<Unit> {
        fake.takeTuner(3, "192.168.1.70")
        sessions.releaseStale(3, 4242)
        assertThat(fake.lockKeyOf(3)).isEqualTo(-1L)
    }

    @Test
    fun `an unreachable device`() = runBlocking<Unit> {
        fake.vanished = true
        assertThat(coroutineScope { sessions.claim(this, vchannel("2.1")) }).isEqualTo(ClaimResult.Unreachable)
    }

    private class RecordingLockKeys : LockKeyStore {
        val held = mutableMapOf<Pair<String, Int>, Long>()
        override suspend fun remember(deviceId: String, tuner: Int, lockKey: Long) { held[deviceId to tuner] = lockKey }
        override suspend fun forget(deviceId: String, tuner: Int) { held.remove(deviceId to tuner) }
    }
}
