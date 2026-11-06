package io.cosgriff.sightline.protocol

import com.google.common.truth.Truth.assertThat
import io.cosgriff.sightline.protocol.fake.FakeHdhr
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.net.InetAddress

class TunerObserverTest {
    private var now = 1_000_000L
    private val fake = FakeHdhr(clock = { now })
    private val control = ControlClient(InetAddress.getByName(fake.host), fake.controlPort)

    @After
    fun tearDown() {
        control.close()
        fake.close()
    }

    @Test
    fun `reads a tuner another client holds without a single set`() = runBlocking<Unit> {
        fake.takeTuner(2, "192.168.1.20", fake.channels[2], networkLossPerSecond = 7)
        val observer = TunerObserver(control, 2)

        val first = observer.read() as Observation.InUse
        assertThat(first.holder).isEqualTo("192.168.1.20")
        assertThat(first.guideNumber).isEqualTo("5.1")
        assertThat(first.target).isEqualTo("http://192.168.1.20:5004")
        assertThat(first.status.isLocked).isTrue()

        now += 3_000
        val later = observer.read() as Observation.InUse
        assertThat(later.debug!!.networkErrors).isEqualTo(21)
        assertThat(fake.setsReceived).isEqualTo(0)
        assertThat(fake.lockKeyOf(2)).isEqualTo(-1L) // still the other client's
    }

    @Test
    fun `an idle tuner, and the next client's channel`() = runBlocking<Unit> {
        val observer = TunerObserver(control, 1)
        assertThat(observer.read()).isEqualTo(Observation.Idle)

        fake.takeTuner(1, "192.168.1.20", fake.channels[0])
        assertThat((observer.read() as Observation.InUse).guideNumber).isEqualTo("2.1")
        fake.freeTuner(1)
        fake.takeTuner(1, "192.168.1.30", fake.channels[4])
        val next = observer.read() as Observation.InUse
        assertThat(next.guideNumber).isEqualTo("9.1")
        assertThat(next.holder).isEqualTo("192.168.1.30")
    }

    @Test
    fun `an unreachable device`() = runBlocking<Unit> {
        fake.vanished = true
        assertThat(TunerObserver(control, 0).read()).isInstanceOf(Observation.Unreachable::class.java)
    }

    @Test
    fun `program numbers map to guide numbers through streaminfo`() {
        val info = "1: 9.1 PROG-1\n2: 9.2 PROG-2\n3: 9.3 PROG-3\ntsid=0x01CF\n"
        assertThat(TunerObserver.programGuideNumber(info, "2")).isEqualTo("9.2")
        assertThat(TunerObserver.programGuideNumber(info, "7")).isNull()
        assertThat(TunerObserver.programGuideNumber("none\n", "1")).isNull()
        assertThat(TunerObserver.streamHost("udp://192.168.1.20:5000")).isEqualTo("192.168.1.20")
        assertThat(TunerObserver.streamHost("none://")).isNull()
    }
}
