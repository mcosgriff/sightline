package io.cosgriff.sightline.debugtools

import com.google.common.truth.Truth.assertThat
import io.cosgriff.sightline.protocol.ControlClient
import io.cosgriff.sightline.protocol.ControlResult
import io.cosgriff.sightline.protocol.fake.FakeHdhr
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.net.InetAddress

/** The debug wiring: two Demo tuners, each its own device, with its own player, firmware and file server. */
class DemoTunerDevicesTest {
    @Test
    fun `debug builds list two demo tuners, and each answers for itself`() = runBlocking<Unit> {
        val devices = DemoTunerDevices.devices
        assertThat(devices.map { it.deviceId }).containsExactly("1D3E0001", "1D3E0002").inOrder()
        assertThat(devices.map { it.friendlyName }).containsExactly("Demo tuner", "Demo tuner 2").inOrder()
        assertThat(devices.all { it.demo && it.tunerCount == 4 }).isTrue()
        assertThat(devices.map { it.controlPort }.toSet()).hasSize(2)
        assertThat(devices.map { it.firmware }).containsExactly("20260326", "20261107").inOrder()
        assertThat(DemoTunerDevices.isDemo("1D3E0002")).isTrue()
        assertThat(DemoTunerDevices.isDemo("1A2B3C4D")).isFalse()

        val urls = devices.map { DemoTunerDevices.firmwareBaseUrl(it.deviceId) }
        assertThat(urls).containsExactly(devices[0].baseUrl, devices[1].baseUrl).inOrder()
        assertThat(DemoTunerDevices.firmwareBaseUrl("1A2B3C4D")).isNull()

        val (first, second) = devices.map { ControlClient(InetAddress.getByName(it.address), it.controlPort) }
        try {
            suspend fun ControlClient.holder(tuner: Int) = (get("/tuner$tuner/lockkey") as ControlResult.Value).value
            assertThat(first.holder(FakeHdhr.DEMO_PLAYER_TUNER)).isEqualTo(FakeHdhr.DEMO_PLAYER)
            assertThat(second.holder(FakeHdhr.SECOND_DEMO_PLAYER_TUNER)).isEqualTo(FakeHdhr.DEMO_PLAYER)

            // Stopping one Demo tuner's player leaves the other's alone.
            DemoTunerDevices.stopDemoPlayer("1D3E0002")
            assertThat(second.holder(FakeHdhr.SECOND_DEMO_PLAYER_TUNER)).isEqualTo("none")
            assertThat(first.holder(FakeHdhr.DEMO_PLAYER_TUNER)).isEqualTo(FakeHdhr.DEMO_PLAYER)
        } finally {
            first.close()
            second.close()
        }
    }
}
