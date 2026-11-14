package io.cosgriff.sightline.protocol

/**
 * `get /sys/debug`, as a FLEX 4K sends it (firmware 20261107):
 *
 * ```
 * mem: ddr=256 nbk=3 dmk=377 fet=0
 * loop: pkt=0
 * t0: pt=12 cal=82
 * eth: link=100f
 * ```
 *
 * Only the Ethernet link is read: `<speed in Mbit/s><f|h>` for full or half duplex. The other lines' meanings
 * aren't documented, so Diagnostics shows them raw.
 */
data class SysDebug(val link: EthernetLink?, val raw: String) {
    companion object {
        private val LINK = Regex("""^eth:.*\blink=(\S+)""", RegexOption.MULTILINE)

        fun parse(text: String): SysDebug {
            val raw = text.trimEnd('\u0000').trimEnd()
            return SysDebug(LINK.find(raw)?.groupValues?.get(1)?.let(EthernetLink::parse), raw)
        }
    }
}

/** The device's Ethernet link. [speedMbps] and [fullDuplex] are null when [raw] isn't `<digits><f|h>`, e.g. `none`. */
data class EthernetLink(val speedMbps: Int?, val fullDuplex: Boolean?, val raw: String) {
    /**
     * A link that can drop a tuner's stream on the way out: half duplex, or slower than 100 Mbit/s (four ATSC 1.0
     * streams are about 80 Mbit/s), or no link at all. Unknown forms aren't called slow.
     */
    val limiting: Boolean
        get() = raw == "none" || fullDuplex == false || (speedMbps != null && speedMbps < 100)

    companion object {
        private val FORM = Regex("""(\d+)([fh])""")

        fun parse(text: String): EthernetLink {
            val m = FORM.matchEntire(text.trim())
            return EthernetLink(m?.groupValues?.get(1)?.toIntOrNull(), m?.groupValues?.get(2)?.let { it == "f" }, text.trim())
        }
    }
}
