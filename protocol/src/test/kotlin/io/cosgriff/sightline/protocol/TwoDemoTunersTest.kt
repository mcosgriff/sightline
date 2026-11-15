package io.cosgriff.sightline.protocol

import com.google.common.truth.Truth.assertThat
import io.cosgriff.sightline.protocol.fake.FakeHdhr
import io.cosgriff.sightline.protocol.fake.Signal
import io.cosgriff.sightline.protocol.fake.UnitFault
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.net.InetAddress
import kotlin.time.Duration.Companion.milliseconds

/** The two Demo tuners debug builds start, as the user's two units: both at once, and a fault on one only. */
class TwoDemoTunersTest {
    private var now = 1_000_000L
    private val first = FakeHdhr.demo(clock = { now })
    private val second = FakeHdhr.secondDemo(clock = { now })
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val firstControl = ControlClient(loopback, first.controlPort)
    private val secondControl = ControlClient(loopback, second.controlPort)

    @After
    fun tearDown() {
        firstControl.close()
        secondControl.close()
        first.close()
        second.close()
    }

    private fun discovery(fake: FakeHdhr) =
        Discovery(PlainDiscoveryEnvironment(listOf(loopback)), port = fake.discoveryPort, collectFor = 300.milliseconds, includeGlobalBroadcast = false)

    private suspend fun ControlClient.status(tuner: Int): TunerStatus =
        (StatusParser.parse((get("/tuner$tuner/status") as ControlResult.Value).value) as StatusParse.Parsed).status

    @Test
    fun `each answers discovery with its own id, name and ports`() = runBlocking<Unit> {
        val a = discovery(first).probe(loopback)!!
        val b = discovery(second).probe(loopback)!!
        assertThat(a.deviceId).isEqualTo("1D3E0001")
        assertThat(b.deviceId).isEqualTo("1D3E0002")
        assertThat(b.controlPort).isNotEqualTo(a.controlPort)
        assertThat(b.baseUrl).isNotEqualTo(a.baseUrl)
        assertThat(first.friendlyName).isEqualTo("Demo tuner")
        assertThat(second.friendlyName).isEqualTo("Demo tuner 2")
    }

    @Test
    fun `neither id passes a real unit's check digit`() {
        assertThat(validDeviceId(FakeHdhr.DEMO_ID)).isFalse()
        assertThat(validDeviceId(FakeHdhr.SECOND_DEMO_ID)).isFalse()
        // The check itself, on IDs that pass it, so a broken check can't pass the Demo IDs by accident.
        assertThat(validDeviceId(0x1D3E0000)).isTrue()
    }

    @Test
    fun `a made-up player holds a tuner on each, from a documentation address`() = runBlocking<Unit> {
        assertThat((firstControl.get("/tuner3/lockkey") as ControlResult.Value).value).isEqualTo("192.0.2.20")
        assertThat((secondControl.get("/tuner0/lockkey") as ControlResult.Value).value).isEqualTo("192.0.2.20")
        assertThat((secondControl.get("/tuner0/vchannel") as ControlResult.Value).value).isEqualTo("4.1")
        assertThat(first.isIdle(0)).isTrue()
        assertThat(second.isIdle(3)).isTrue()
        assertThat(first.setsReceived + second.setsReceived).isEqualTo(0)
    }

    @Test
    fun `the second carries the same stations, a little weaker, and has no update on offer`() = runBlocking<Unit> {
        assertThat(second.channels.map { it.guideNumber to it.rf }).isEqualTo(first.channels.map { it.guideNumber to it.rf })
        val steadyFirst = first.channels.first { it.guideNumber == "2.1" }.pattern.at(10_000)
        val steadySecond = second.channels.first { it.guideNumber == "2.1" }.pattern.at(10_000)
        assertThat(steadySecond.ss).isEqualTo(steadyFirst.ss - 4)
        assertThat(steadySecond.snq).isEqualTo(steadyFirst.snq - 4)
        assertThat(first.upgradeAvailable).isEqualTo(first.latestFirmware)
        assertThat(second.upgradeAvailable).isNull()
        assertThat(second.firmwareVersion).isEqualTo(second.latestFirmware)
    }

    @Test
    fun `a fault on one unit drops every tuner on it and none on the other`() = runBlocking<Unit> {
        // The same channel on an idle tuner of each, as a two-unit comparison tunes it.
        firstControl.set("/tuner1/lockkey", "11")
        firstControl.set("/tuner1/vchannel", "2.1", 11)
        secondControl.set("/tuner1/lockkey", "22")
        secondControl.set("/tuner1/vchannel", "2.1", 22)
        now += 5_000
        assertThat(firstControl.status(1).isLocked).isTrue()
        assertThat(secondControl.status(1).isLocked).isTrue()

        second.fault = UnitFault.dropout(fromMs = now + 1_000, untilMs = now + 11_000)
        now += 3_000
        assertThat(secondControl.status(1).isLocked).isFalse()
        assertThat(secondControl.status(0).isLocked).isFalse() // the made-up player's tuner too: the whole unit
        assertThat(firstControl.status(1).isLocked).isTrue()
        assertThat(firstControl.status(3).isLocked).isTrue()

        now += 10_000
        assertThat(secondControl.status(1).isLocked).isTrue()

        firstControl.set("/tuner1/lockkey", "none", 11)
        secondControl.set("/tuner1/lockkey", "none", 22)
    }

    @Test
    fun `a recurring fault drops the signal at the end of each period of clock time`() {
        val fault = UnitFault.recurring(everyMs = 900_000, lengthMs = 20_000)
        val good = Signal(true, 80, 84, 100)
        assertThat(fault.at(900_000L * 7 + 879_999, good)).isEqualTo(good)
        assertThat(fault.at(900_000L * 7 + 880_000, good)).isEqualTo(Signal.NONE)
        assertThat(fault.at(900_000L * 8 - 1, good)).isEqualTo(Signal.NONE)
        assertThat(fault.at(900_000L * 8, good)).isEqualTo(good)
    }

    /** libhdhomerun's `hdhomerun_discover_validate_device_id`: every real unit's ID passes it. */
    private fun validDeviceId(id: Long): Boolean {
        val table = intArrayOf(0xA, 0x5, 0xF, 0x6, 0x7, 0xC, 0x1, 0xB, 0x9, 0x2, 0x8, 0xD, 0x4, 0x3, 0xE, 0x0)
        var checksum = 0
        for (shift in 28 downTo 0 step 4) {
            val nibble = ((id shr shift) and 0xF).toInt()
            checksum = checksum xor if ((shift / 4) % 2 == 1) table[nibble] else nibble
        }
        return checksum == 0
    }
}
