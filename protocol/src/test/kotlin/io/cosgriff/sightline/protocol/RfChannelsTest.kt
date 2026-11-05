package io.cosgriff.sightline.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RfChannelsTest {
    @Test
    fun `captured frequencies map to their rf channels`() {
        assertThat(RfChannels.byFrequency(189_000_000)).isEqualTo(RfChannel(9, 189_000_000, Band.VHF_HI))
        assertThat(RfChannels.byFrequency(485_000_000)?.number).isEqualTo(16)
        assertThat(RfChannels.byFrequency(593_000_000)).isEqualTo(RfChannel(34, 593_000_000, Band.UHF))
    }

    @Test
    fun `band edges`() {
        assertThat(RfChannels.byNumber(2)).isEqualTo(RfChannel(2, 57_000_000, Band.VHF_LO))
        assertThat(RfChannels.byNumber(4)?.centerHz).isEqualTo(69_000_000)
        assertThat(RfChannels.byNumber(5)).isEqualTo(RfChannel(5, 79_000_000, Band.VHF_LO))
        assertThat(RfChannels.byNumber(6)?.band).isEqualTo(Band.VHF_LO)
        assertThat(RfChannels.byNumber(7)).isEqualTo(RfChannel(7, 177_000_000, Band.VHF_HI))
        assertThat(RfChannels.byNumber(13)).isEqualTo(RfChannel(13, 213_000_000, Band.VHF_HI))
        assertThat(RfChannels.byNumber(14)).isEqualTo(RfChannel(14, 473_000_000, Band.UHF))
        assertThat(RfChannels.byNumber(36)).isEqualTo(RfChannel(36, 605_000_000, Band.UHF))
        assertThat(RfChannels.all).hasSize(35)
    }

    @Test
    fun `outside the map is null`() {
        assertThat(RfChannels.byNumber(1)).isNull()
        assertThat(RfChannels.byNumber(37)).isNull()
        assertThat(RfChannels.byFrequency(74_000_000)).isNull() // the 72–76 MHz gap
        assertThat(RfChannels.byFrequency(615_000_000)).isNull()
    }

    @Test
    fun `any frequency in the 6 MHz slot finds the channel`() {
        assertThat(RfChannels.byFrequency(186_000_000)?.number).isEqualTo(9)
        assertThat(RfChannels.byFrequency(191_999_999)?.number).isEqualTo(9)
        assertThat(RfChannels.byFrequency(192_000_000)?.number).isEqualTo(10)
    }

    @Test
    fun `from a status ch field`() {
        assertThat(RfChannels.of(TunedChannel.Auto(9))?.number).isEqualTo(9)
        assertThat(RfChannels.of(TunedChannel.Frequency(Modulation.ATSC3, 593_000_000, "1"))?.number).isEqualTo(34)
        assertThat(RfChannels.of(TunedChannel.None)).isNull()
    }
}
