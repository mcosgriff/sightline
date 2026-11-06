package io.cosgriff.sightline.protocol

/**
 * `get /tunerN/debug`, parsed. Four lines, each a section name and `key=value` pairs:
 *
 * ```
 * tun: ch=8vsb:189000000 lock=8vsb:189000000 ss=100 snq=97 seq=100 dbg=10-136/558
 * dev: bps=19636224 resync=0 overflow=0
 * ts:  bps=8335168 te=0 crc=0
 * net: bps=0 pps=0 err=0 stop=0
 * ```
 *
 * `ts te` (transport errors) and `crc` count damaged packets since the tune, so they catch errors between polls
 * that one `seq` snapshot misses. `dev resync`/`overflow` are the demodulator losing sync or its buffer
 * overrunning; `net err`/`stop` are the tuner failing to send its stream onto the network. Every number is null
 * when the firmware leaves it out. `dbg` is opaque and kept as sent.
 */
data class TunerDebug(
    val ss: Int?,
    val snq: Int?,
    val seq: Int?,
    val dbg: String?,
    val deviceBps: Long?,
    val resync: Long?,
    val overflow: Long?,
    val transportBps: Long?,
    val transportErrors: Long?,
    val crcErrors: Long?,
    val networkBps: Long?,
    val networkPps: Long?,
    val networkErrors: Long?,
    val networkStops: Long?,
    val raw: String,
) {
    companion object {
        /** Lenient like [StatusParser]: sections and keys in any order, unknown ones ignored. Null when no section reads. */
        fun parse(text: String): TunerDebug? {
            val raw = text.trimEnd('\u0000').trimEnd()
            val sections = raw.lineSequence().mapNotNull { line ->
                val name = line.substringBefore(':', missingDelimiterValue = "").trim()
                if (name.isEmpty()) return@mapNotNull null
                name to line.substringAfter(':').trim().split(WHITESPACE)
                    .mapNotNull { pair -> pair.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
                    .toMap()
            }.toMap()
            if (sections.isEmpty()) return null
            fun long(section: String, key: String) = sections[section]?.get(key)?.toLongOrNull()
            fun int(section: String, key: String) = sections[section]?.get(key)?.toIntOrNull()
            return TunerDebug(
                ss = int("tun", "ss"),
                snq = int("tun", "snq"),
                seq = int("tun", "seq"),
                dbg = sections["tun"]?.get("dbg"),
                deviceBps = long("dev", "bps"),
                resync = long("dev", "resync"),
                overflow = long("dev", "overflow"),
                transportBps = long("ts", "bps"),
                transportErrors = long("ts", "te"),
                crcErrors = long("ts", "crc"),
                networkBps = long("net", "bps"),
                networkPps = long("net", "pps"),
                networkErrors = long("net", "err"),
                networkStops = long("net", "stop"),
                raw = raw,
            )
        }

        private val WHITESPACE = Regex("\\s+")
    }
}
