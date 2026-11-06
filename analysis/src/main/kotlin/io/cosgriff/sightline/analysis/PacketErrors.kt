package io.cosgriff.sightline.analysis

/**
 * A tuner's error counters as read with one reading (`/tunerN/debug`): each is the count since that tuner's last
 * tune, null when the firmware didn't give it. See `TunerDebug` in `:protocol`.
 */
data class ErrorCounters(
    val transport: Long?,
    val crc: Long?,
    val resyncs: Long?,
    val network: Long?,
)

/**
 * Where packet errors happened (SPEC "Packet errors"): on the way in from the antenna (transport, CRC, resyncs),
 * on the way out to the client over the network, or both.
 */
enum class ErrorSource { NONE, SIGNAL, NETWORK, BOTH }

/** Errors counted over some stretch: transport (damaged packets), CRC, demodulator resyncs, network send errors. */
data class PacketErrors(val transport: Long = 0, val crc: Long = 0, val resyncs: Long = 0, val network: Long = 0) {
    val any: Boolean get() = transport > 0 || crc > 0 || resyncs > 0 || network > 0

    /** Errors on the antenna side: everything but [network]. */
    val signal: Long get() = transport + crc + resyncs

    val source: ErrorSource
        get() = when {
            signal > 0 && network > 0 -> ErrorSource.BOTH
            signal > 0 -> ErrorSource.SIGNAL
            network > 0 -> ErrorSource.NETWORK
            else -> ErrorSource.NONE
        }

    operator fun plus(o: PacketErrors) = PacketErrors(transport + o.transport, crc + o.crc, resyncs + o.resyncs, network + o.network)

    companion object {
        val NONE = PacketErrors()
    }
}

/**
 * The errors each reading added, from the running counters it carried (SPEC "Packet errors"). The counters reset
 * on every tune, so a reading on a different channel than the tuner's previous one, or with a counter lower than
 * before, starts a new tune; the first reading of a tune is its baseline and adds nothing, so errors from tuning
 * itself (before the first reading) never count.
 *
 * [hasCounters] is false when no reading carried any (sessions from before this was recorded, or a firmware
 * without `/tunerN/debug`), so screens can say "not recorded" rather than "0".
 */
class PacketErrorSeries private constructor(private val atMs: LongArray, private val added: List<PacketErrors>, val hasCounters: Boolean) {
    val total: PacketErrors by lazy { added.fold(PacketErrors.NONE, PacketErrors::plus) }

    /** Errors added by readings from [startMs] to [endMs], both included. */
    fun between(startMs: Long, endMs: Long): PacketErrors {
        var sum = PacketErrors.NONE
        var i = lowerBound(startMs)
        while (i < atMs.size && atMs[i] <= endMs) {
            sum += added[i]
            i++
        }
        return sum
    }

    private fun lowerBound(ms: Long): Int {
        var lo = 0
        var hi = atMs.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (atMs[mid] < ms) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        /** [readings] in time order, as in [SessionData]. */
        fun of(readings: List<LoggedReading>): PacketErrorSeries {
            val counter = PacketErrorCounter()
            val added = readings.map { r -> r.counters?.let { counter.add(r.tuner, r.guideNumber, it) } ?: PacketErrors.NONE }
            return PacketErrorSeries(LongArray(readings.size) { readings[it].atMs }, added, readings.any { it.counters != null })
        }
    }
}

/**
 * Turns running counters into the errors each reading added, one reading at a time: what [PacketErrorSeries] does
 * for a stored session, and Monitor for its running total, so both count the same way.
 */
class PacketErrorCounter {
    private data class Last(val guideNumber: String, val counters: ErrorCounters)

    private val last = HashMap<Int, Last>()

    /** The errors since [tuner]'s previous reading of the same tune; none for the first reading of a tune. */
    fun add(tuner: Int, guideNumber: String, counters: ErrorCounters): PacketErrors {
        val before = last[tuner]?.takeIf { it.guideNumber == guideNumber }?.counters
        last[tuner] = Last(guideNumber, counters)
        if (before == null || fell(counters, before)) return PacketErrors.NONE
        return PacketErrors(
            transport = step(counters.transport, before.transport),
            crc = step(counters.crc, before.crc),
            resyncs = step(counters.resyncs, before.resyncs),
            network = step(counters.network, before.network),
        )
    }

    /** The tuner was tuned again: its next reading is a baseline, even on the same channel. */
    fun retuned(tuner: Int) {
        last.remove(tuner)
    }

    private companion object {
        /** Any counter lower than before: the tuner was tuned again in between. */
        fun fell(c: ErrorCounters, before: ErrorCounters) =
            listOf(c.transport to before.transport, c.crc to before.crc, c.resyncs to before.resyncs, c.network to before.network)
                .any { (now, was) -> now != null && was != null && now < was }

        private fun step(now: Long?, was: Long?): Long = if (now != null && was != null) now - was else 0
    }
}
