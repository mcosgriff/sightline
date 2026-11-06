package io.cosgriff.sightline.protocol

/** One read of a tuner Sightline doesn't hold (GLOSSARY "Watch a tuner in use"). */
sealed interface Observation {
    /**
     * Tuned by someone. [holder] is the lock holder's IP, or the stream target's host; null when neither says.
     * [guideNumber] is what was tuned: `vchannel`, or the program being sent (`program` looked up in `streaminfo`);
     * null when the tuner says neither (tuned by RF). [target] is where it streams (`http://<ip>:<port>`), if
     * anywhere. [debug] is null when `/tunerN/debug` didn't answer.
     */
    data class InUse(
        val status: TunerStatus,
        val debug: TunerDebug?,
        val holder: String?,
        val guideNumber: String?,
        val target: String?,
    ) : Observation

    /** `ch=none`: nobody is using it. */
    data object Idle : Observation

    data class Unparseable(val raw: String) : Observation

    data class Unreachable(val reason: UnreachableReason) : Observation
}

/**
 * Reads a tuner without holding it: gets only, never a set, so a DVR or Plex using it can't be disturbed (CLAUDE.md:
 * never take a tuner in use). Each [read] is `status` and `debug`; who holds it and what was tuned are read again
 * only when `ch=` changes, so a 1 Hz watch costs two gets a second.
 */
class TunerObserver(private val control: ControlClient, val tuner: Int) {
    private var lastChannel: TunedChannel? = null
    private var holder: String? = null
    private var guideNumber: String? = null
    private var target: String? = null

    suspend fun read(): Observation {
        val status = when (val r = control.get("/tuner$tuner/status")) {
            is ControlResult.Value -> when (val p = StatusParser.parse(r.value)) {
                is StatusParse.Parsed -> p.status
                is StatusParse.Unparseable -> return Observation.Unparseable(p.raw)
            }
            is ControlResult.Rejected -> return Observation.Unparseable(r.error.message)
            is ControlResult.Unreachable -> return Observation.Unreachable(r.reason)
        }
        if (status.isIdle) {
            lastChannel = null
            return Observation.Idle
        }
        val debug = (control.get("/tuner$tuner/debug") as? ControlResult.Value)?.value?.let { TunerDebug.parse(it) }
        if (status.channel != lastChannel) {
            val lockKey = value("lockkey")
            target = value("target")
            holder = lockKey ?: target?.let(::streamHost)
            guideNumber = value("vchannel") ?: programGuideNumber()
            lastChannel = status.channel
        }
        return Observation.InUse(status, debug, holder, guideNumber, target)
    }

    /** A value, or null for `none`, an error or no answer. */
    private suspend fun value(item: String): String? =
        (control.get("/tuner$tuner/$item") as? ControlResult.Value)?.value?.trim()?.takeIf { it.isNotEmpty() && it != "none" }

    private suspend fun programGuideNumber(): String? {
        val program = value("program") ?: return null
        return value("streaminfo")?.let { programGuideNumber(it, program) }
    }

    companion object {
        /** The host of a `target` value (`http://192.168.1.20:5004`, `udp://…`); null when it has none. */
        fun streamHost(target: String): String? =
            target.substringAfter("://").substringBefore('/').substringBefore(':').ifEmpty { null }

        /** In `streaminfo` (`1: 9.1 KUSA-HD` per line), the guide number of [program]; null when it isn't listed. */
        fun programGuideNumber(streamInfo: String, program: String): String? = streamInfo.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.substringBefore(':', "").trim() == program }
            ?.substringAfter(':')?.trim()?.substringBefore(' ')
            ?.takeIf { it.isNotEmpty() && it.first().isDigit() }
    }
}
