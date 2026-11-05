package io.cosgriff.sightline.protocol

/** A packet in a capture file: `>` sent by the client, `<` sent by the device, with the `#` line before it. */
data class CapturedPacket(val fromDevice: Boolean, val bytes: ByteArray, val note: String)

/** Reads a file from `docs/protocol-captures` (on the test classpath; see protocol/build.gradle.kts). */
object Captures {
    fun text(name: String): String =
        requireNotNull(javaClass.classLoader.getResource(name)) { "no capture $name" }.readText()

    fun packets(name: String): List<CapturedPacket> {
        var note = ""
        return text(name).lines().mapNotNull { line ->
            when {
                line.startsWith("# ") -> { note = line.removePrefix("# "); null }
                line.startsWith("> ") -> CapturedPacket(false, hex(line.substring(2)), note)
                line.startsWith("< ") -> CapturedPacket(true, hex(line.substring(2)), note)
                else -> null
            }
        }
    }

    val controlFiles = listOf(
        "control-get-idle.txt",
        "control-tune-8vsb.txt",
        "control-tune-rf.txt",
        "control-tune-atsc3-syntax.txt",
        "control-status-atsc3-http.txt",
    )

    fun hex(s: String): ByteArray = s.trim().chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

/** The value of a decoded get/set reply, or its error message. */
fun Packet.valueOrError(): String = (this[Tag.GETSET_VALUE] ?: this[Tag.ERROR_MESSAGE])!!.string()

fun decodeOk(bytes: ByteArray): Packet = (Packet.decode(bytes) as DecodeResult.Ok).packet
