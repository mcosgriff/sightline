package io.cosgriff.sightline.protocol

import com.google.common.truth.Truth.assertThat
import io.cosgriff.sightline.protocol.fake.FakeHdhr
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class ControlClientTest {
    private val fake = FakeHdhr()
    private val time = TestTimeSource()
    private val client = ControlClient(fake.bindAddressForTests(), fake.controlPort, 1.seconds, time)

    @After
    fun tearDown() {
        client.close()
        fake.close()
    }

    @Test
    fun `gets over one connection`() = runBlocking<Unit> {
        assertThat(client.get("/sys/hwmodel")).isEqualTo(ControlResult.Value("HDFX-4K"))
        assertThat(client.get("/tuner0/status")).isEqualTo(ControlResult.Value("ch=none lock=none ss=0 snq=0 seq=0 bps=0 pps=0"))
        assertThat(client.localAddress).isEqualTo(InetAddress.getByName("127.0.0.1"))
    }

    @Test
    fun `errors come back as their kind`() = runBlocking<Unit> {
        assertThat((client.get("/tuner0/vstatus") as ControlResult.Rejected).error)
            .isInstanceOf(ControlError.UnknownVariable::class.java)
        assertThat(client.set("/tuner0/lockkey", "1234")).isEqualTo(ControlResult.Value("127.0.0.1"))
        val other = ControlClient(fake.bindAddressForTests(), fake.controlPort)
        val refused = other.set("/tuner0/channel", "none") as ControlResult.Rejected
        assertThat(refused.error).isEqualTo(ControlError.Locked("127.0.0.1", "ERROR: resource locked by 127.0.0.1"))
        other.close()
    }

    @Test
    fun `a set with the lock key is taken`() = runBlocking<Unit> {
        client.set("/tuner1/lockkey", "77")
        assertThat(client.set("/tuner1/vchannel", "2.1", 77)).isEqualTo(ControlResult.Value("2.1"))
        assertThat(client.set("/tuner1/vchannel", "4.1", 78)).isInstanceOf(ControlResult.Rejected::class.java)
        assertThat(client.set("/tuner1/vchannel", "102.1", 77))
            .isEqualTo(ControlResult.Rejected(ControlError.InvalidVirtualChannel("ERROR: invalid virtual channel")))
    }

    @Test
    fun `a lost device is unreachable, backs off, and is reached again`() = runBlocking<Unit> {
        assertThat(client.get("/sys/version")).isInstanceOf(ControlResult.Value::class.java)
        fake.vanished = true
        assertThat(client.get("/sys/version")).isInstanceOf(ControlResult.Unreachable::class.java)
        // Within the 1 s delay the next call doesn't even try.
        assertThat(client.get("/sys/version")).isEqualTo(ControlResult.Unreachable(UnreachableReason.BACKING_OFF))
        fake.vanished = false
        time += 1.seconds
        assertThat(client.get("/sys/version")).isEqualTo(ControlResult.Value("20260326"))
    }

    @Test
    fun `nothing listening is a connect failure`() = runBlocking<Unit> {
        val port = ServerSocket(0).use { it.localPort }
        val nobody = ControlClient(InetAddress.getLoopbackAddress(), port, 500.milliseconds, time)
        assertThat(nobody.get("/sys/version")).isEqualTo(ControlResult.Unreachable(UnreachableReason.CONNECT_FAILED))
        assertThat(nobody.get("/sys/version")).isEqualTo(ControlResult.Unreachable(UnreachableReason.BACKING_OFF))
    }

    @Test
    fun `error messages`() {
        assertThat(ControlError.of("ERROR: resource locked by 192.168.1.21"))
            .isEqualTo(ControlError.Locked("192.168.1.21", "ERROR: resource locked by 192.168.1.21"))
        assertThat(ControlError.of("ERROR: invalid channel")).isInstanceOf(ControlError.InvalidChannel::class.java)
        assertThat(ControlError.of("ERROR: something new")).isInstanceOf(ControlError.Other::class.java)
    }
}

private fun FakeHdhr.bindAddressForTests(): InetAddress = InetAddress.getByName(host)
