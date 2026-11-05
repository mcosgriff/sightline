package io.cosgriff.sightline.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.net.InetAddress

/** Every packet captured from the user's FLEX 4K units (docs/protocol-captures) through the codec and parsers. */
class CapturesTest {
    @Test
    fun `every captured packet decodes with a good crc and re-encodes to the same bytes`() {
        for (file in Captures.controlFiles + "discover-udp.txt") {
            val packets = Captures.packets(file)
            assertThat(packets).isNotEmpty()
            for (p in packets) {
                val decoded = Packet.decode(p.bytes)
                assertThat(decoded).isInstanceOf(DecodeResult.Ok::class.java)
                val again = Packet((decoded as DecodeResult.Ok).packet.type, decoded.packet.tlvs).encode()
                assertThat(again.toHex()).isEqualTo(p.bytes.toHex())
            }
        }
    }

    @Test
    fun `discovery replies give each device once, with its tuners and urls`() {
        val replies = Captures.packets("discover-udp.txt").filter { it.fromDevice }
        val devices = replies.mapNotNull { r ->
            Discovery.parseReply(r.bytes, InetAddress.getByName(r.note.removePrefix("from ")))
        }.distinctBy { it.deviceId }

        assertThat(replies).hasSize(4) // two per device: with and without tag 0x2D
        assertThat(devices.map { it.deviceId }).containsExactly("1A2B3C4D", "1A2B3C5E")
        val first = devices.first { it.deviceId == "1A2B3C4D" }
        assertThat(first.tunerCount).isEqualTo(4)
        assertThat(first.baseUrl).isEqualTo("http://192.168.1.18:80")
        assertThat(first.lineupUrl).isEqualTo("http://192.168.1.18:80/lineup.json")
    }

    @Test
    fun `every captured status line parses`() {
        val lines = Captures.controlFiles.flatMap { Captures.packets(it) }
            .filter { it.fromDevice }
            .map { decodeOk(it.bytes) }
            .filter { it[Tag.GETSET_NAME]?.string()?.endsWith("/status") == true && it[Tag.GETSET_VALUE] != null }
            .map { it.valueOrError() }
        assertThat(lines.size).isAtLeast(20)
        for (line in lines) assertThat(StatusParser.parse(line)).isInstanceOf(StatusParse.Parsed::class.java)
    }

    @Test
    fun `captured errors map to their kinds`() {
        val errors = Captures.controlFiles.flatMap { Captures.packets(it) }
            .filter { it.fromDevice }
            .mapNotNull { decodeOk(it.bytes)[Tag.ERROR_MESSAGE]?.string() }
            .map { ControlError.of(it) }
        assertThat(errors.filterIsInstance<ControlError.Locked>().map { it.byAddress }.toSet()).containsExactly("192.168.1.21")
        assertThat(errors.any { it is ControlError.UnknownVariable }).isTrue()
        assertThat(errors.any { it is ControlError.InvalidVirtualChannel }).isTrue()
        assertThat(errors.any { it is ControlError.InvalidChannel }).isTrue()
        assertThat(errors.filterIsInstance<ControlError.Other>()).isEmpty()
    }

    @Test
    fun `the redacted device auth never reaches the json models`() {
        val info = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(DeviceInfo.serializer(), Captures.text("discover-18.json"))
        assertThat(info.modelNumber).isEqualTo("HDFX-4K")
        assertThat(info.tunerCount).isEqualTo(4)
        assertThat(info.toString()).doesNotContain("REDACTED")
    }

    @Test
    fun `the captured lineup parses`() {
        val lineup = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(kotlinx.serialization.builtins.ListSerializer(LineupEntry.serializer()), Captures.text("lineup.json"))
        assertThat(lineup.map { it.guideNumber }).containsAtLeast("9.1", "102.1")
        val atsc3 = lineup.first { it.guideNumber == "102.1" }
        assertThat(HttpApi.streamUrl(atsc3.url, 1)).isEqualTo("http://192.168.1.18:5004/tuner1/v102.1")
    }
}
