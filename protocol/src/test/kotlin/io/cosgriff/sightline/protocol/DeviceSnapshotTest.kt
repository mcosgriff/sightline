package io.cosgriff.sightline.protocol

import com.google.common.truth.Truth.assertThat
import io.cosgriff.sightline.protocol.fake.FakeHdhr
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Test
import java.net.InetAddress

class DeviceSnapshotTest {
    private var now = 1_000_000L
    private val fake = FakeHdhr(clock = { now })
    private val control = ControlClient(InetAddress.getByName(fake.host), fake.controlPort)

    @After
    fun tearDown() {
        control.close()
        fake.close()
    }

    @Test
    fun `reads system and every tuner without a single set`() = runBlocking<Unit> {
        fake.takeTuner(2, "192.168.1.60", fake.channels.first())
        now += 5_000
        val snap = DeviceSnapshot.read(control, fake.tunerCount)

        assertThat(fake.setsReceived).isEqualTo(0)
        assertThat(snap.unreachable).isFalse()
        assertThat(snap.system.map { it.name }).isEqualTo(DeviceSnapshot.SYSTEM)
        assertThat((snap.system.first { it.name == "/sys/version" }.result as ControlResult.Value).value).isEqualTo("20260326")
        assertThat(snap.tuners).hasSize(4)
        assertThat(snap.localAddress).isEqualTo("127.0.0.1")

        val idle = snap.tuners[0]
        assertThat(idle.status?.isIdle).isTrue()
        assertThat(idle.lockHolder).isNull()
        assertThat(idle.debug?.transportErrors).isEqualTo(0)

        val busy = snap.tuners[2]
        assertThat(busy.lockHolder).isEqualTo("192.168.1.60")
        assertThat(busy.status?.isLocked).isTrue()
        assertThat(busy.debug?.ss).isNotNull()
    }

    @Test
    fun `a device that doesn't answer is unreachable, and tuners aren't asked`() = runBlocking<Unit> {
        fake.close()
        val snap = DeviceSnapshot.read(control, 4)
        assertThat(snap.unreachable).isTrue()
        assertThat(snap.tuners.all { it.probes.isEmpty() }).isTrue()
    }

    @Test
    fun `discover json passes on an offered upgrade and nothing else new`() = runBlocking<Unit> {
        val http = HttpApi(OkHttpClient())
        assertThat((http.deviceInfo(fake.baseUrl) as HttpResult.Ok).value.upgradeAvailable).isNull()
        fake.upgradeAvailable = "20261020"
        val info = (http.deviceInfo(fake.baseUrl) as HttpResult.Ok).value
        assertThat(info.upgradeAvailable).isEqualTo("20261020")
        assertThat(info.toString()).doesNotContain("DEMODEMO") // DeviceAuth never reaches the model
    }
}
