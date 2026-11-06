package io.cosgriff.sightline.analysis

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PacketErrorSeriesTest {
    private val ok = SignalSample(true, 90, 85, 100)

    private fun r(at: Long, te: Long?, crc: Long? = 0, guide: String = "9.1", tuner: Int = 0) =
        LoggedReading(at, guide, tuner, ok, counters = te?.let { ErrorCounters(it, crc, 0, 0) })

    @Test
    fun `adds what each reading's counters grew by, from the first reading on`() {
        val s = PacketErrorSeries.of(listOf(r(0, 50, 5), r(1_000, 50, 5), r(2_000, 80, 9), r(3_000, 200, 30)))
        assertThat(s.hasCounters).isTrue()
        // 50 errors before the first reading are tuning, not counted.
        assertThat(s.total).isEqualTo(PacketErrors(transport = 150, crc = 25))
        assertThat(s.between(1_500, 2_500)).isEqualTo(PacketErrors(transport = 30, crc = 4))
        assertThat(s.between(0, 1_000)).isEqualTo(PacketErrors.NONE)
    }

    @Test
    fun `a counter that falls, or a new channel, is a new tune`() {
        val s = PacketErrorSeries.of(
            listOf(
                r(0, 10), r(1_000, 20), // +10
                r(2_000, 3), r(3_000, 7), // retuned: baseline 3, +4
                r(4_000, 100, guide = "4.1"), r(5_000, 101, guide = "4.1"), // rotate: baseline 100, +1
            ),
        )
        assertThat(s.total.transport).isEqualTo(15)
    }

    @Test
    fun `tuners are counted apart, and a missing read keeps the last baseline`() {
        val s = PacketErrorSeries.of(listOf(r(0, 0, tuner = 0), r(0, 500, tuner = 1), r(1_000, null), r(2_000, 4, tuner = 0), r(2_000, 510, tuner = 1)))
        assertThat(s.total.transport).isEqualTo(14)
    }

    @Test
    fun `no counters at all says so`() {
        val s = PacketErrorSeries.of(listOf(r(0, null), r(1_000, null)))
        assertThat(s.hasCounters).isFalse()
        assertThat(s.total.any).isFalse()
    }

    @Test
    fun `errors say where they happened`() {
        assertThat(PacketErrors.NONE.source).isEqualTo(ErrorSource.NONE)
        assertThat(PacketErrors(transport = 3).source).isEqualTo(ErrorSource.SIGNAL)
        assertThat(PacketErrors(resyncs = 1).source).isEqualTo(ErrorSource.SIGNAL)
        assertThat(PacketErrors(network = 9).source).isEqualTo(ErrorSource.NETWORK)
        assertThat(PacketErrors(crc = 1, network = 9).source).isEqualTo(ErrorSource.BOTH)
        assertThat(PacketErrors(transport = 4, crc = 1, resyncs = 2, network = 9).signal).isEqualTo(7)
    }
}
