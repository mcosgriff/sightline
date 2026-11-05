package io.cosgriff.sightline.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PacketTest {
    @Test
    fun `a get encodes byte for byte as the real client sent it`() {
        val packet = Packet(PacketType.GETSET_REQUEST, listOf(Tlv.string(Tag.GETSET_NAME, "/sys/model")))
        assertThat(packet.encode().toHex()).isEqualTo("0004000d030b2f7379732f6d6f64656c008f5b54b5")
    }

    @Test
    fun `a set with a lock key encodes as captured`() {
        val packet = Packet(
            PacketType.GETSET_REQUEST,
            listOf(
                Tlv.string(Tag.GETSET_NAME, "/tuner3/vchannel"),
                Tlv.string(Tag.GETSET_VALUE, "9.1"),
                Tlv.uint32(Tag.LOCK_KEY, 2407659871L),
            ),
        )
        val captured = Captures.packets("control-tune-8vsb.txt").first { it.note.startsWith("A tune set /tuner3/vchannel 9.1") }
        assertThat(packet.encode().toHex()).isEqualTo(captured.bytes.toHex())
    }

    @Test
    fun `the discover request is the one the tuners answered`() {
        val sent = Captures.packets("discover-udp.txt").first { !it.fromDevice }
        assertThat(Discovery.discoverRequest().encode().toHex()).isEqualTo(sent.bytes.toHex())
    }

    @Test
    fun `round trip keeps type and every tlv`() {
        val packet = Packet(
            PacketType.GETSET_REPLY,
            listOf(Tlv.string(Tag.GETSET_NAME, "/tuner0/status"), Tlv(0x7E, byteArrayOf(1, 2, 3)), Tlv(0x01, ByteArray(0))),
        )
        val back = decodeOk(packet.encode())
        assertThat(back.type).isEqualTo(PacketType.GETSET_REPLY)
        assertThat(back.tlvs.map { it.tag to it.value.toList() })
            .containsExactlyElementsIn(packet.tlvs.map { it.tag to it.value.toList() }).inOrder()
    }

    @Test
    fun `sizes up to 127 take one byte and 128 takes two`() {
        assertThat(Packet.encodeVarLen(0).toList()).containsExactly(0x00.toByte())
        assertThat(Packet.encodeVarLen(127).toList()).containsExactly(0x7F.toByte())
        assertThat(Packet.encodeVarLen(128).toList()).containsExactly(0x80.toByte(), 0x01.toByte()).inOrder()
        assertThat(Packet.encodeVarLen(139).toList()).containsExactly(0x8B.toByte(), 0x01.toByte()).inOrder()
        assertThat(Packet.encodeVarLen(0x7FFF).toList()).containsExactly(0xFF.toByte(), 0xFF.toByte()).inOrder()
    }

    @Test
    fun `values of 127 and 128 bytes round trip`() {
        for (size in listOf(126, 127, 128, 129, 300)) {
            val value = ByteArray(size) { it.toByte() }
            val back = decodeOk(Packet(PacketType.GETSET_REPLY, listOf(Tlv(Tag.GETSET_VALUE, value))).encode())
            assertThat(back[Tag.GETSET_VALUE]!!.value.toList()).isEqualTo(value.toList())
        }
    }

    @Test
    fun `a real reply with a two byte size decodes`() {
        // /sys/features: a 139-byte value, sent as 8b 01.
        val reply = Captures.packets("control-get-idle.txt").first { it.fromDevice && it.note.contains("/sys/features") }
        assertThat(reply.bytes.toHex()).contains("048b01")
        assertThat(decodeOk(reply.bytes).valueOrError()).contains("modulation: atsc3 8vsb qam256 qam64")
    }

    @Test
    fun `crc matches known vectors`() {
        // The standard CRC-32 check value, and a captured frame's trailer (little-endian on the wire).
        assertThat(Packet.crc("123456789".toByteArray(), 9)).isEqualTo(0xCBF43926L)
        val frame = Captures.hex("0004000d030b2f7379732f6d6f64656c008f5b54b5")
        assertThat(Packet.crc(frame, frame.size - 4)).isEqualTo(0xB5545B8FL)
    }

    @Test
    fun `malformed packets are reported, never thrown`() {
        val good = Packet(PacketType.GETSET_REQUEST, listOf(Tlv.string(Tag.GETSET_NAME, "/sys/model"))).encode()

        assertThat(Packet.decode(ByteArray(3))).isEqualTo(DecodeResult.Malformed(Malformation.TOO_SHORT))
        assertThat(Packet.decode(good.copyOf(good.size - 1))).isEqualTo(DecodeResult.Malformed(Malformation.LENGTH_MISMATCH))
        assertThat(Packet.decode(good + 0)).isEqualTo(DecodeResult.Malformed(Malformation.LENGTH_MISMATCH))
        assertThat(Packet.decode(good.copyOf().also { it[6] = 'X'.code.toByte() }))
            .isEqualTo(DecodeResult.Malformed(Malformation.BAD_CRC))
        assertThat(Packet.decode(reframe(good.copyOf().also { it[1] = 0x09 })))
            .isEqualTo(DecodeResult.Malformed(Malformation.UNKNOWN_TYPE))
        // A TLV whose size says 0x7F bytes in an 11-byte payload.
        assertThat(Packet.decode(reframe(good.copyOf().also { it[5] = 0x7F })))
            .isEqualTo(DecodeResult.Malformed(Malformation.TRUNCATED_TLV))
        // A two-byte size cut off by the end of the payload.
        assertThat(Packet.decode(reframe(byteArrayOf(0, 5, 0, 2, 0x03, 0x80.toByte(), 0, 0, 0, 0))))
            .isEqualTo(DecodeResult.Malformed(Malformation.TRUNCATED_TLV))
    }

    @Test
    fun `numbers read as unsigned`() {
        assertThat(Tlv.uint32(Tag.DEVICE_ID, 0xFFFFFFFFL).uint32()).isEqualTo(0xFFFFFFFFL)
        assertThat(Tlv(Tag.TUNER_COUNT, byteArrayOf(4)).uint()).isEqualTo(4)
        assertThat(Tlv(Tag.TUNER_COUNT, byteArrayOf(4)).uint32()).isNull()
    }

    /** Recomputes the CRC so only the change under test is wrong. */
    private fun reframe(frame: ByteArray): ByteArray {
        val body = frame.copyOf(frame.size - 4)
        val crc = Packet.crc(body, body.size)
        return body + byteArrayOf(crc.toByte(), (crc shr 8).toByte(), (crc shr 16).toByte(), (crc shr 24).toByte())
    }
}
