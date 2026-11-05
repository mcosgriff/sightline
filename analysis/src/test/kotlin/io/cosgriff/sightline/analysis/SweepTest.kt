package io.cosgriff.sightline.analysis

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SweepTest {
    private val t = Thresholds.DEFAULT
    private fun s(snq: Int, seq: Int = 100, locked: Boolean = true) = SignalSample(locked, 70, snq, seq)
    private fun ch(guide: String, vararg samples: SignalSample) = SweptChannel.of(guide, samples.toList(), t)

    @Test
    fun `range and lower median over locked readings`() {
        val c = ch("2.1", s(50), s(58), s(54), s(61))
        assertThat(listOf(c.minSnq, c.medianSnq, c.maxSnq)).containsExactly(50, 54, 61).inOrder()
        assertThat(c.health).isEqualTo(Health.POOR)
    }

    @Test
    fun `health from the median reading, seq included`() {
        assertThat(ch("9.1", s(88), s(90), s(86)).health).isEqualTo(Health.GOOD)
        assertThat(ch("4.1", s(72), s(70)).health).isEqualTo(Health.MARGINAL)
        assertThat(ch("5.1", s(88, seq = 95), s(88, seq = 95)).health).isEqualTo(Health.MARGINAL)
    }

    @Test
    fun `a dropout in the dwell is poor, never locking is no lock, nothing read is unmeasured`() {
        assertThat(ch("7.1", s(90), s(0, locked = false), s(90)).health).isEqualTo(Health.POOR)
        val none = ch("8.1", s(0, locked = false))
        assertThat(none.health).isEqualTo(Health.NO_LOCK)
        assertThat(none.medianSnq).isNull()
        assertThat(ch("9.9").health).isNull()
        assertThat(ch("9.9").measured).isFalse()
    }

    @Test
    fun `summary counts no lock as poor and sorts worst first, unmeasured last`() {
        val summary = SweepSummary(
            listOf(ch("9.1", s(88)), ch("2.1", s(54)), ch("x", ), ch("4.1", s(72)), ch("8.1", s(0, locked = false)), ch("7.1", s(86))),
        )
        assertThat(listOf(summary.good, summary.marginal, summary.poor)).containsExactly(2, 1, 2).inOrder()
        assertThat(summary.worstFirst.map { it.guideNumber }).containsExactly("8.1", "2.1", "4.1", "7.1", "9.1", "x").inOrder()
    }

    @Test
    fun `comparison counts changes of three points or more, and lock gained or lost`() {
        val before = listOf(ch("2.1", s(50)), ch("4.1", s(72)), ch("7.1", s(86)), ch("8.1", s(0, locked = false)), ch("9.1", s(88)), ch("gone", s(80)))
        val after = listOf(ch("2.1", s(60)), ch("4.1", s(70)), ch("7.1", s(80)), ch("8.1", s(65)), ch("9.1"), ch("new", s(80)))
        val c = SweepComparison.of(before, after)
        assertThat(c.deltas.associate { it.guideNumber to it.change }).containsExactly(
            "2.1", ChannelChange.BETTER,
            "4.1", ChannelChange.SAME,
            "7.1", ChannelChange.WORSE,
            "8.1", ChannelChange.BETTER,
            "9.1", ChannelChange.NOT_COMPARABLE,
            "new", ChannelChange.NEW,
            "gone", ChannelChange.GONE,
        )
        assertThat(c.deltas.first { it.guideNumber == "2.1" }.snqDelta).isEqualTo(10)
        assertThat(c.better).isEqualTo(2)
        assertThat(c.worse).isEqualTo(1)
        assertThat(c.deltas.last().guideNumber).isEqualTo("gone")
    }
}
