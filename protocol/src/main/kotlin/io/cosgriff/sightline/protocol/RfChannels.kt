package io.cosgriff.sightline.protocol

/** US broadcast bands. VHF and UHF problems have different causes, so the band is shown with the channel. */
enum class Band { VHF_LO, VHF_HI, UHF }

/** One US broadcast RF channel (`us-bcast` channel map) and the centre frequency the tuner reports for it. */
data class RfChannel(val number: Int, val centerHz: Long, val band: Band)

/**
 * The US broadcast channel map after the 2020 repack: VHF-lo 2–6, VHF-hi 7–13, UHF 14–36. The tuner reports the
 * channel's centre frequency (`ch=8vsb:189000000` is RF 9, captured on a FLEX 4K).
 */
object RfChannels {
    val all: List<RfChannel> = buildList {
        // 2–4 and 5–6 are split by the 72–76 MHz gap.
        for (n in 2..4) add(RfChannel(n, mhz(57 + 6 * (n - 2)), Band.VHF_LO))
        for (n in 5..6) add(RfChannel(n, mhz(79 + 6 * (n - 5)), Band.VHF_LO))
        for (n in 7..13) add(RfChannel(n, mhz(177 + 6 * (n - 7)), Band.VHF_HI))
        for (n in 14..36) add(RfChannel(n, mhz(473 + 6 * (n - 14)), Band.UHF))
    }

    fun byNumber(number: Int): RfChannel? = all.firstOrNull { it.number == number }

    /** The channel whose 6 MHz slot holds [hz], or null outside the map. */
    fun byFrequency(hz: Long): RfChannel? = all.firstOrNull { hz in (it.centerHz - HALF_WIDTH) until (it.centerHz + HALF_WIDTH) }

    /** The RF channel a status `ch=` names, by number for `auto:<rf>` or by frequency otherwise. */
    fun of(channel: TunedChannel): RfChannel? = when (channel) {
        is TunedChannel.Auto -> byNumber(channel.rfChannel)
        is TunedChannel.Frequency -> byFrequency(channel.hz)
        TunedChannel.None, is TunedChannel.Unknown -> null
    }

    private const val HALF_WIDTH = 3_000_000L
    private fun mhz(v: Int) = v * 1_000_000L
}
