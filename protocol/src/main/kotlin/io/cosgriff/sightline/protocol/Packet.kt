package io.cosgriff.sightline.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** Packet types on UDP and TCP port 65001 (libhdhomerun `hdhomerun_pkt.h`). */
enum class PacketType(val code: Int) {
    DISCOVER_REQUEST(0x0002),
    DISCOVER_REPLY(0x0003),
    GETSET_REQUEST(0x0004),
    GETSET_REPLY(0x0005),
    ;

    companion object {
        fun of(code: Int): PacketType? = entries.firstOrNull { it.code == code }
    }
}

/** TLV tags Sightline reads or writes. Others in a reply are kept as they come, under their raw tag. */
object Tag {
    const val DEVICE_TYPE = 0x01
    const val DEVICE_ID = 0x02
    const val GETSET_NAME = 0x03
    const val GETSET_VALUE = 0x04
    const val ERROR_MESSAGE = 0x05
    const val TUNER_COUNT = 0x10
    const val LOCK_KEY = 0x15
    const val LINEUP_URL = 0x27
    const val BASE_URL = 0x2A

    /** The device's cloud credential. Sightline never stores or logs it. */
    const val DEVICE_AUTH = 0x2B
}

/** Device type values for discovery. */
object DeviceType {
    const val TUNER = 0x00000001L
    const val WILDCARD = 0xFFFFFFFFL
}

/** One tag-length-value entry. */
class Tlv(val tag: Int, val value: ByteArray) {
    /** The value as null-terminated ASCII, the way names, values, errors and URLs are sent. */
    fun string(): String {
        val end = value.indexOf(0).takeIf { it >= 0 } ?: value.size
        return String(value, 0, end, Charsets.US_ASCII)
    }

    /** The value as a big-endian uint32. Null when it isn't four bytes. */
    fun uint32(): Long? = if (value.size == 4) ByteBuffer.wrap(value).int.toLong() and 0xFFFFFFFFL else null

    /** The value as an unsigned number of one to four bytes, big-endian (tuner count is one byte). */
    fun uint(): Long? =
        if (value.size in 1..4) value.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) } else null

    override fun toString() = "Tlv(0x%02x, %d bytes)".format(tag, value.size)

    companion object {
        fun string(tag: Int, s: String) = Tlv(tag, s.toByteArray(Charsets.US_ASCII) + 0)

        fun uint32(tag: Int, v: Long) =
            Tlv(tag, ByteBuffer.allocate(4).putInt((v and 0xFFFFFFFFL).toInt()).array())
    }
}

/** One protocol packet: a type and its TLVs. The frame (lengths and CRC) is added by [encode]. */
class Packet(val type: PacketType, val tlvs: List<Tlv>) {
    /** The first TLV with [tag], or null. */
    operator fun get(tag: Int): Tlv? = tlvs.firstOrNull { it.tag == tag }

    fun encode(): ByteArray {
        val payload = ByteArrayOutputStream()
        for (tlv in tlvs) {
            payload.write(tlv.tag)
            payload.write(encodeVarLen(tlv.value.size))
            payload.write(tlv.value)
        }
        val body = payload.toByteArray()
        require(body.size <= MAX_PAYLOAD) { "payload of ${body.size} bytes is over $MAX_PAYLOAD" }
        val frame = ByteBuffer.allocate(HEADER + body.size + CRC_SIZE)
        frame.putShort(type.code.toShort()).putShort(body.size.toShort()).put(body)
        frame.order(ByteOrder.LITTLE_ENDIAN).putInt(crc(frame.array(), HEADER + body.size).toInt())
        return frame.array()
    }

    companion object {
        const val HEADER = 4
        const val CRC_SIZE = 4

        /** Largest TLV value or payload the codec handles: the two-byte size goes to 0x7FFF. */
        const val MAX_PAYLOAD = 0x7FFF

        /**
         * Decodes exactly one packet from [bytes]. Anything wrong (short, bad length, bad CRC, unknown type, a TLV
         * running off the end) is a [DecodeResult.Malformed], never an exception: replies come off the network.
         */
        fun decode(bytes: ByteArray): DecodeResult {
            if (bytes.size < HEADER + CRC_SIZE) return DecodeResult.Malformed(Malformation.TOO_SHORT)
            val buf = ByteBuffer.wrap(bytes)
            val typeCode = buf.short.toInt() and 0xFFFF
            val length = buf.short.toInt() and 0xFFFF
            if (bytes.size != HEADER + length + CRC_SIZE) return DecodeResult.Malformed(Malformation.LENGTH_MISMATCH)
            val expected = ByteBuffer.wrap(bytes, HEADER + length, CRC_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                .int.toLong() and 0xFFFFFFFFL
            if (crc(bytes, HEADER + length) != expected) return DecodeResult.Malformed(Malformation.BAD_CRC)
            val type = PacketType.of(typeCode) ?: return DecodeResult.Malformed(Malformation.UNKNOWN_TYPE)

            val tlvs = mutableListOf<Tlv>()
            var i = HEADER
            val end = HEADER + length
            while (i < end) {
                val tag = bytes[i++].toInt() and 0xFF
                if (i >= end) return DecodeResult.Malformed(Malformation.TRUNCATED_TLV)
                var size = bytes[i++].toInt() and 0xFF
                if (size and 0x80 != 0) {
                    if (i >= end) return DecodeResult.Malformed(Malformation.TRUNCATED_TLV)
                    size = (size and 0x7F) or ((bytes[i++].toInt() and 0xFF) shl 7)
                }
                if (i + size > end) return DecodeResult.Malformed(Malformation.TRUNCATED_TLV)
                tlvs += Tlv(tag, bytes.copyOfRange(i, i + size))
                i += size
            }
            return DecodeResult.Ok(Packet(type, tlvs))
        }

        /**
         * A TLV size: one byte under 128, otherwise two, low seven bits first with the high bit set
         * (`(len and 0x7F) or 0x80`, then `len shr 7`).
         */
        fun encodeVarLen(size: Int): ByteArray {
            require(size in 0..MAX_PAYLOAD) { "TLV size $size out of range" }
            return if (size < 0x80) byteArrayOf(size.toByte())
            else byteArrayOf(((size and 0x7F) or 0x80).toByte(), (size shr 7).toByte())
        }

        /** Standard CRC-32 over the first [length] bytes: type, length and payload. */
        fun crc(bytes: ByteArray, length: Int): Long = CRC32().apply { update(bytes, 0, length) }.value

        /** Total frame size once [header] (the first four bytes) is known, for reading a packet off a stream. */
        fun frameSize(header: ByteArray): Int {
            require(header.size >= HEADER)
            val length = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
            return HEADER + length + CRC_SIZE
        }
    }
}

sealed interface DecodeResult {
    data class Ok(val packet: Packet) : DecodeResult
    data class Malformed(val why: Malformation) : DecodeResult
}

enum class Malformation { TOO_SHORT, LENGTH_MISMATCH, BAD_CRC, UNKNOWN_TYPE, TRUNCATED_TLV }
