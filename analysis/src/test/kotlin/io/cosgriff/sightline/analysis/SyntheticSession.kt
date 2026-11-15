package io.cosgriff.sightline.analysis

import io.cosgriff.sightline.protocol.Band
import io.cosgriff.sightline.protocol.fake.SignalPattern

/** Builds synthetic sessions for the diagnosis tests: readings from patterns, events from the real dip detector. */
class SyntheticSession(
    val startMs: Long = START,
    val pollMs: Long = 10_000,
    val thresholds: Thresholds = Thresholds.DEFAULT,
) {
    val readings = mutableListOf<LoggedReading>()
    val extraEvents = mutableListOf<SignalEvent>()
    val weather = mutableListOf<WeatherHour>()
    val channels = mutableMapOf<String, ChannelInfo>()

    /** The phone's Wi-Fi, as a monitor would have sampled it; empty, as in sessions from before it was kept. */
    val wifi = mutableListOf<WifiSample>()
    var endMs = startMs

    /**
     * [guide] read on [tuner] from [fromMs] to [toMs] after the start, every [pollMs], following [pattern]. [device]
     * names the tuner's device in a session over two (empty: the session's device), as a two-unit compare stores it.
     */
    fun read(
        guide: String,
        pattern: SignalPattern,
        fromMs: Long = 0,
        toMs: Long,
        tuner: Int = 0,
        rf: Int? = null,
        band: Band? = null,
        device: String = "",
    ) {
        var t = fromMs
        while (t < toMs) {
            val s = pattern.at(t)
            readings += LoggedReading(startMs + t, guide, tuner, SignalSample(s.locked, s.ss, s.snq, s.seq), deviceId = device)
            t += pollMs
        }
        if (rf != null || band != null) channels[guide] = ChannelInfo(rf, band)
        endMs = maxOf(endMs, startMs + toMs)
    }

    fun hour(index: Int, tempC: Double? = 10.0, gustKph: Double? = 5.0, precipMm: Double? = 0.0) {
        weather += WeatherHour(startMs + index * HOUR, tempC, gustKph?.let { it / 2 }, gustKph, precipMm)
    }

    /**
     * The dip detector run over each channel's readings, as a monitor holding each would record; one detector per
     * device and channel, each event naming its device when the readings do (as a two-unit compare's do).
     */
    fun events(): List<SignalEvent> = readings.groupBy { it.deviceId to it.guideNumber }.flatMap { (key, rs) ->
        val d = DipDetector(thresholds)
        val events = rs.flatMap { d.reading(it.atMs, it.guideNumber, it.sample) } + d.finish(rs.last().atMs)
        if (key.first.isEmpty()) events else events.map { it.copy(deviceId = key.first) }
    }

    fun data(): SessionData {
        val sorted = readings.sortedBy { it.atMs }
        return SessionData(startMs, endMs, pollMs, sorted, (events() + extraEvents).sortedBy { it.startMs }, weather.toList(), channels.toMap(), wifi = wifi.sortedBy { it.atMs })
    }

    fun diagnose(): List<Finding> = Diagnosis.run(data(), thresholds)

    companion object {
        const val HOUR = 3_600_000L
        const val MINUTE = 60_000L

        /** 2026-11-02T00:00Z, on an hour. */
        const val START = 1_793_577_600_000L

        /** A constant signal. */
        fun fixed(ss: Int, snq: Int, seq: Int = 100, locked: Boolean = true) =
            SignalPattern { io.cosgriff.sightline.protocol.fake.Signal(locked, ss, snq, seq) }

        /** [base] most of the time, [bad] for [lengthMs] every [everyMs]. */
        fun dipping(base: SignalPattern, bad: SignalPattern, everyMs: Long, lengthMs: Long = 40_000) =
            SignalPattern { t -> if (t % everyMs < lengthMs) bad.at(t) else base.at(t) }
    }
}
