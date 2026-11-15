package io.cosgriff.sightline.analysis

/** The Wi-Fi band a frequency is in. */
enum class WifiBand {
    GHZ_2_4,
    GHZ_5,
    GHZ_6,
    ;

    companion object {
        /** The band of [mhz]; null for a frequency in none of them. */
        fun of(mhz: Int): WifiBand? = when (mhz) {
            in 2_400..2_500 -> GHZ_2_4
            in 4_900..5_900 -> GHZ_5
            in 5_925..7_125 -> GHZ_6
            else -> null
        }
    }
}

/**
 * The phone's own Wi-Fi from [atMs] until the next sample (SPEC "Phone Wi-Fi"): a change the platform reported
 * during a monitor session. [connected] false: the phone was off Wi-Fi, and the rest is null. Each other field is
 * null when the platform didn't give it; [bssid] (the access point) also when Sightline has no precise location.
 */
data class WifiSample(
    val atMs: Long,
    val connected: Boolean,
    val rssiDbm: Int? = null,
    val linkMbps: Int? = null,
    val frequencyMhz: Int? = null,
    val bssid: String? = null,
) {
    val band: WifiBand? get() = frequencyMhz?.let(WifiBand::of)
}

/** How good a Wi-Fi signal is, as the lane colours it: good at [GOOD_DBM] or better, poor at [WEAK_DBM] or weaker. */
object WifiSignal {
    const val GOOD_DBM = -67

    /** The weak line: the Phone Wi-Fi rule counts a signal this weak or weaker. */
    const val WEAK_DBM = -80

    fun health(rssiDbm: Int): Health = when {
        rssiDbm >= GOOD_DBM -> Health.GOOD
        rssiDbm > WEAK_DBM -> Health.MARGINAL
        else -> Health.POOR
    }
}

/** The times in [samples] (in time order) the phone moved to another access point: a known BSSID changing while on Wi-Fi. */
fun roamsOf(samples: List<WifiSample>): List<Long> = samples.zipWithNext().mapNotNull { (a, b) ->
    b.atMs.takeIf { a.connected && b.connected && a.bssid != null && b.bssid != null && a.bssid != b.bssid }
}

/** The times in [samples] (in time order) the phone went off Wi-Fi: from on to off, or off at the first sample. */
fun disconnectsOf(samples: List<WifiSample>): List<Long> =
    samples.withIndex().filter { (i, s) -> !s.connected && (i == 0 || samples[i - 1].connected) }.map { it.value.atMs }

/**
 * A session's Wi-Fi in a line (SPEC Screens › Session detail, "Phone Wi-Fi"): the signal range, the bands, the
 * roams and the times off Wi-Fi, and the access points seen, in the order first seen. Null with no samples.
 */
data class WifiSummary(
    val samples: Int,
    val minRssiDbm: Int?,
    val maxRssiDbm: Int?,
    val bands: List<WifiBand>,
    val roams: Int,
    val disconnects: Int,
    val accessPoints: List<String>,
    /** On Wi-Fi at some point without a BSSID ever known: Sightline had no precise location. */
    val accessPointsUnknown: Boolean,
) {
    companion object {
        fun of(samples: List<WifiSample>): WifiSummary? {
            if (samples.isEmpty()) return null
            val on = samples.filter { it.connected }
            val rssi = on.mapNotNull { it.rssiDbm }
            val aps = on.mapNotNull { it.bssid }.distinct()
            return WifiSummary(
                samples = samples.size,
                minRssiDbm = rssi.minOrNull(),
                maxRssiDbm = rssi.maxOrNull(),
                bands = on.mapNotNull { it.band }.distinct().sorted(),
                roams = roamsOf(samples).size,
                disconnects = disconnectsOf(samples).size,
                accessPoints = aps,
                accessPointsUnknown = on.isNotEmpty() && aps.isEmpty(),
            )
        }
    }
}
