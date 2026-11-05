package io.cosgriff.sightline.analysis

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MonitorTest {
    private fun locked(snq: Int) = SignalSample(true, 80, snq, 100)

    @Test
    fun `a bucket keeps its lowest snq, and no lock counts as 0`() {
        var s = TimelineSeries(startMs = 1_000, bucketMs = 10_000)
        s += 1_000L to locked(85)
        s += 5_000L to locked(42)
        s += 9_000L to locked(80)
        s += 12_000L to SignalSample(false, 0, 0, 0)
        assertThat(s.lows).containsExactly(0, 42, 1, 0)
    }

    @Test
    fun `too many buckets join in pairs and the width doubles`() {
        var s = TimelineSeries(startMs = 0, bucketMs = 1_000, maxBuckets = 4)
        for (t in 0L until 4) s += t * 1000 to locked(80 - t.toInt())
        s += 4_000L to locked(90)
        assertThat(s.bucketMs).isEqualTo(2_000)
        assertThat(s.lows).containsExactly(0, 79, 1, 77, 2, 90)
        assertThat(s.position(3_000)).isEqualTo(1.5)
    }

    @Test
    fun `readings from before the start are ignored`() {
        val s = TimelineSeries(startMs = 10_000, bucketMs = 1_000) + (5_000L to locked(50))
        assertThat(s.lows).isEmpty()
    }

    @Test
    fun `a planned session fits without doubling, an open-ended one starts fine`() {
        val twelveHours = 12 * 3_600_000L
        val planned = TimelineSeries.forPlan(0, twelveHours, 1_000)
        assertThat(twelveHours / planned.bucketMs).isLessThan(TimelineSeries.DEFAULT_MAX_BUCKETS.toLong())
        assertThat(TimelineSeries.forPlan(0, null, 1_000).bucketMs).isEqualTo(5_000)
        // Never finer than a poll.
        assertThat(TimelineSeries.forPlan(0, 60_000, 5_000).bucketMs).isEqualTo(5_000)
    }

    private fun dip(guide: String, endMs: Long) = SignalEvent(EventKind.DIP, guide, endMs - 5_000, endMs)

    @Test
    fun `dip alerts come at most every 10 minutes and sum the last hour`() {
        val alerts = DipAlerts()
        val min = 60_000L
        assertThat(alerts.onEvent(dip("9.1", 1 * min), 1 * min)).isEqualTo(DipAlert(1, listOf("9.1")))
        assertThat(alerts.onEvent(dip("9.1", 5 * min), 5 * min)).isNull()
        assertThat(alerts.onEvent(dip("4.1", 8 * min), 8 * min)).isNull()
        assertThat(alerts.onEvent(dip("9.1", 11 * min), 11 * min)).isEqualTo(DipAlert(4, listOf("9.1", "4.1")))
        // An hour on, the early ones have dropped out of the count.
        assertThat(alerts.onEvent(dip("4.1", 69 * min), 69 * min)).isEqualTo(DipAlert(2, listOf("9.1", "4.1")))
    }

    @Test
    fun `alerts ignore events that are not dips`() {
        val alerts = DipAlerts()
        assertThat(alerts.onEvent(SignalEvent(EventKind.GAP, null, 0, 100_000), 100_000)).isNull()
        assertThat(alerts.onEvent(dip("9.1", 110_000), 110_000)).isEqualTo(DipAlert(1, listOf("9.1")))
    }

    @Test
    fun `twelve hours at one reading a second is about 43,000 readings and 3 MB`() {
        val e = MonitorEstimate.of(12 * 3_600_000L, 1_000)
        assertThat(e.readings).isEqualTo(43_200)
        assertThat(e.bytes).isIn(2_900_000L..3_100_000L)
        assertThat(MonitorEstimate.of(3_600_000L, 5_000).readings).isEqualTo(720)
    }
}
