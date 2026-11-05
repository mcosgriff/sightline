package io.cosgriff.sightline.analysis

import com.google.common.truth.Truth.assertThat
import io.cosgriff.sightline.analysis.SyntheticSession.Companion.HOUR
import io.cosgriff.sightline.analysis.SyntheticSession.Companion.START
import io.cosgriff.sightline.analysis.SyntheticSession.Companion.fixed
import io.cosgriff.sightline.protocol.fake.SignalPattern
import org.junit.Test
import java.time.ZoneId

class SessionStatsTest {
    private fun reading(atMs: Long, snq: Int, guide: String = "9.1", locked: Boolean = true, ss: Int = 80, seq: Int = 100) =
        LoggedReading(START + atMs, guide, 0, SignalSample(locked, ss, snq, seq))

    private fun data(readings: List<LoggedReading>, events: List<SignalEvent> = emptyList(), zone: ZoneId = ZoneId.of("UTC")) =
        SessionData(START, readings.maxOf { it.atMs }, 1_000, readings, events, zone = zone)

    @Test
    fun `summary figures`() {
        val readings = listOf(reading(0, 90), reading(1_000, 85), reading(2_000, 50), reading(3_000, 0, locked = false))
        val events = listOf(
            SignalEvent(EventKind.DIP, "9.1", START, START + 4_000),
            SignalEvent(EventKind.DROPOUT, "9.1", START, START + 64_000),
            SignalEvent(EventKind.DROPOUT, "9.1", START, START + 9_000),
            SignalEvent(EventKind.GAP, "9.1", START, START + 99_000),
        )
        val stats = SessionStats.of(data(readings, events), Thresholds.DEFAULT)
        assertThat(stats.readings).isEqualTo(4)
        assertThat(stats.goodShare).isEqualTo(0.5)
        assertThat(stats.dips).isEqualTo(3)
        assertThat(stats.longestDropoutMs).isEqualTo(64_000)
        assertThat(stats.medianSnq).isEqualTo(85)
        assertThat(stats.health).isEqualTo(Health.GOOD)
    }

    @Test
    fun `no dropout gives no longest dropout`() {
        val stats = SessionStats.of(data(listOf(reading(0, 90))), Thresholds.DEFAULT)
        assertThat(stats.longestDropoutMs).isNull()
    }

    @Test
    fun `never locked is No lock with no median`() {
        val stats = SessionStats.of(data(listOf(reading(0, 0, locked = false))), Thresholds.DEFAULT)
        assertThat(stats.health).isEqualTo(Health.NO_LOCK)
        assertThat(stats.medianSnq).isNull()
    }

    @Test
    fun `histogram bins of five, 100 in the last`() {
        val readings = listOf(0, 4, 5, 79, 80, 99, 100).mapIndexed { i, v -> reading(i * 1_000L, v) } + reading(9_000, 0, locked = false)
        val h = SessionStats.of(data(readings), Thresholds.DEFAULT).histogram
        assertThat(h.counts).hasSize(20)
        assertThat(h.counts[0]).isEqualTo(2)
        assertThat(h.counts[1]).isEqualTo(1)
        assertThat(h.counts[15]).isEqualTo(1)
        assertThat(h.counts[16]).isEqualTo(1)
        assertThat(h.counts[19]).isEqualTo(2)
        assertThat(h.counts.sum()).isEqualTo(7)
    }

    @Test
    fun `hour cells use local time and pick the worst day`() {
        val s = SyntheticSession(pollMs = 60_000).apply {
            // 2 AM UTC on day one half bad; on day two all bad. Everything else good.
            read("9.1", SignalPattern { t ->
                val h = t / HOUR
                val bad = (h == 2L && (t / 60_000) % 2 == 0L) || h == 26L
                (if (bad) fixed(80, 40) else fixed(80, 88)).at(t)
            }, toMs = 48 * HOUR)
        }
        val utc = SessionStats.of(s.data(), Thresholds.DEFAULT).hours.getValue("9.1")
        assertThat(utc[2]!!.belowShare).isWithin(1e-9).of(0.75)
        assertThat(utc[2]!!.worstHourMs).isEqualTo(START + 26 * HOUR)
        assertThat(utc[3]!!.belowShare).isEqualTo(0.0)

        // Two hours behind UTC: the same readings fall at midnight local.
        val local = SessionStats.of(s.data().copy(zone = ZoneId.of("-02:00")), Thresholds.DEFAULT).hours.getValue("9.1")
        assertThat(local[0]!!.belowShare).isWithin(1e-9).of(0.75)
    }

    @Test
    fun `channel ranges in the order first read`() {
        val readings = listOf(reading(0, 70, "4.1"), reading(0, 90, "9.1"), reading(1_000, 60, "4.1"))
        val stats = SessionStats.of(data(readings), Thresholds.DEFAULT)
        assertThat(stats.channels.map { it.guideNumber }).containsExactly("4.1", "9.1").inOrder()
        assertThat(stats.channels[0].minSnq).isEqualTo(60)
        assertThat(stats.channels[0].maxSnq).isEqualTo(70)
    }

    @Test
    fun `timelines for one window line up`() {
        val a = timelineOf(listOf(reading(0, 80)), START, START + 10 * HOUR)
        val b = timelineOf(listOf(reading(10 * HOUR, 70)), START, START + 10 * HOUR)
        assertThat(a.bucketMs).isEqualTo(b.bucketMs)
        assertThat(b.lows.keys.single()).isEqualTo(TimelineSeries.DEFAULT_MAX_BUCKETS)
    }

    @Test
    fun `sparkline has its points, null where nothing was read`() {
        val line = sparklineOf(listOf(reading(0, 80), reading(HOUR / 2, 40)), START, START + HOUR, points = 4)
        assertThat(line).containsExactly(80, null, 40, null).inOrder()
    }
}
