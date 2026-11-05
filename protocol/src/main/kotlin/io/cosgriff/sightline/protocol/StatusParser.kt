package io.cosgriff.sightline.protocol

/** Modulations the tuner reports in `lock=` and in the `ch=` prefix. Anything else is [OTHER], raw text kept. */
enum class Modulation(val wire: String?) {
    VSB8("8vsb"),
    QAM256("qam256"),
    QAM64("qam64"),
    ATSC3("atsc3"),
    OTHER(null),
    ;

    companion object {
        fun of(wire: String): Modulation = entries.firstOrNull { it.wire == wire } ?: OTHER
    }
}

/** What `ch=` says the tuner was asked for. */
sealed interface TunedChannel {
    /** `ch=none`: the tuner is idle. */
    data object None : TunedChannel

    /** `ch=auto:<rf>`: tuned by RF channel number. The firmware keeps reporting it this way, not as a frequency. */
    data class Auto(val rfChannel: Int) : TunedChannel

    /**
     * `ch=<mod>:<freq Hz>`, after tuning by virtual channel. ATSC 3.0 adds a third field
     * (`atsc3:593000000:1`), kept as [extra].
     */
    data class Frequency(val modulation: Modulation, val hz: Long, val extra: String? = null) : TunedChannel

    /** A form Sightline doesn't know; the raw text is kept. */
    data class Unknown(val raw: String) : TunedChannel
}

/** What `lock=` says. */
sealed interface Lock {
    data object None : Lock

    data class Locked(val modulation: Modulation, val raw: String) : Lock
}

/**
 * One `get /tunerN/status` line, parsed. Numbers are as the tuner sent them: with `lock=none` it can still report
 * ss (and the first reading after a tune can show a lock with seq and bps still 0); deciding what that means is
 * analysis's job, not the parser's. [bps] and [pps] are null when absent. ATSC 3.0 always reports `bps=0`.
 */
data class TunerStatus(
    val channel: TunedChannel,
    val lock: Lock,
    val ss: Int,
    val snq: Int,
    val seq: Int,
    val bps: Long?,
    val pps: Long?,
    val raw: String,
) {
    val isIdle get() = channel == TunedChannel.None
    val isLocked get() = lock is Lock.Locked
}

sealed interface StatusParse {
    data class Parsed(val status: TunerStatus) : StatusParse

    /** A line without the keys Sightline needs, or with values it can't read. Shown raw, never guessed at. */
    data class Unparseable(val raw: String) : StatusParse
}

/**
 * Reads a status line such as `ch=8vsb:189000000 lock=8vsb ss=100 snq=97 seq=100 bps=7831328 pps=0`. Lenient: keys
 * in any order, unknown keys ignored, whitespace of any width. `ch`, `lock`, `ss`, `snq` and `seq` are required;
 * without one the line is [StatusParse.Unparseable] rather than a reading of 0, which would look like a dropout.
 */
object StatusParser {
    fun parse(line: String): StatusParse {
        val raw = line.trim().trimEnd('\u0000')
        val fields = raw.split(WHITESPACE).mapNotNull { token ->
            val eq = token.indexOf('=')
            if (eq <= 0) null else token.substring(0, eq) to token.substring(eq + 1)
        }.toMap()

        val bad = StatusParse.Unparseable(raw)
        val channel = parseChannel(fields["ch"] ?: return bad)
        val lock = parseLock(fields["lock"] ?: return bad)
        val ss = fields["ss"]?.toIntOrNull() ?: return bad
        val snq = fields["snq"]?.toIntOrNull() ?: return bad
        val seq = fields["seq"]?.toIntOrNull() ?: return bad
        val bps = fields["bps"]?.let { it.toLongOrNull() ?: return bad }
        val pps = fields["pps"]?.let { it.toLongOrNull() ?: return bad }
        return StatusParse.Parsed(TunerStatus(channel, lock, ss, snq, seq, bps, pps, raw))
    }

    fun parseChannel(value: String): TunedChannel {
        if (value == "none") return TunedChannel.None
        val parts = value.split(':')
        if (parts.size >= 2 && parts[0] == "auto") {
            return parts[1].toIntOrNull()?.takeIf { parts.size == 2 }?.let { TunedChannel.Auto(it) }
                ?: TunedChannel.Unknown(value)
        }
        if (parts.size in 2..3) {
            val hz = parts[1].toLongOrNull()
            if (hz != null) return TunedChannel.Frequency(Modulation.of(parts[0]), hz, parts.getOrNull(2))
        }
        return TunedChannel.Unknown(value)
    }

    /** `lock=8vsb` in status; `/tunerN/debug` writes `lock=8vsb:189000000`, so a frequency suffix is dropped. */
    fun parseLock(value: String): Lock {
        if (value == "none") return Lock.None
        val mod = value.substringBefore(':')
        return Lock.Locked(Modulation.of(mod), value)
    }

    private val WHITESPACE = Regex("\\s+")
}
