package io.cosgriff.sightline.protocol

import com.google.common.truth.Truth.assertThat
import io.cosgriff.sightline.protocol.fake.FakeHdhr
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.net.InetAddress
import kotlin.time.Duration.Companion.milliseconds

class DiscoveryTest {
    private val fake = FakeHdhr()
    private val loopback = InetAddress.getByName("127.0.0.1")

    @After
    fun tearDown() = fake.close()

    private fun discovery() = Discovery(
        PlainDiscoveryEnvironment(listOf(loopback)),
        port = fake.discoveryPort,
        collectFor = 300.milliseconds,
        includeGlobalBroadcast = false,
    )

    @Test
    fun `finds the device with its id, tuners and urls`() = runBlocking<Unit> {
        val found = discovery().discover()
        assertThat(found).hasSize(1)
        with(found.single()) {
            assertThat(deviceId).isEqualTo(fake.deviceIdHex)
            assertThat(tunerCount).isEqualTo(4)
            assertThat(baseUrl).isEqualTo(fake.baseUrl)
            assertThat(lineupUrl).isEqualTo("${fake.baseUrl}/lineup.json")
            assertThat(address).isEqualTo(loopback)
            assertThat(controlPort).isEqualTo(fake.controlPort) // discovery and control share a port, as on a real unit
        }
    }

    @Test
    fun `probe asks one address`() = runBlocking<Unit> {
        assertThat(discovery().probe(loopback)?.deviceId).isEqualTo(fake.deviceIdHex)
    }

    @Test
    fun `a vanished device is not found`() = runBlocking<Unit> {
        fake.vanished = true
        assertThat(discovery().discover()).isEmpty()
        assertThat(discovery().probe(loopback)).isNull()
    }

    @Test
    fun `a reply from a non-tuner device or garbage is ignored`() {
        val storage = Packet(
            PacketType.DISCOVER_REPLY,
            listOf(Tlv.uint32(Tag.DEVICE_TYPE, 0x00000005), Tlv.uint32(Tag.DEVICE_ID, 0x12345678)),
        ).encode()
        assertThat(Discovery.parseReply(storage, loopback)).isNull()
        assertThat(Discovery.parseReply(byteArrayOf(1, 2, 3), loopback)).isNull()
        assertThat(Discovery.parseReply(Discovery.discoverRequest().encode(), loopback)).isNull()
    }

    @Test
    fun `without a base url tag the address stands in`() {
        val bare = Packet(
            PacketType.DISCOVER_REPLY,
            listOf(Tlv.uint32(Tag.DEVICE_TYPE, DeviceType.TUNER), Tlv.uint32(Tag.DEVICE_ID, 0x0000ABCD)),
        ).encode()
        val device = Discovery.parseReply(bare, InetAddress.getByName("192.168.1.9"))!!
        assertThat(device.deviceId).isEqualTo("0000ABCD")
        assertThat(device.baseUrl).isEqualTo("http://192.168.1.9")
        assertThat(device.tunerCount).isEqualTo(0)
    }
}
