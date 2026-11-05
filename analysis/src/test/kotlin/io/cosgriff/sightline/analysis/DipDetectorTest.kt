package io.cosgriff.sightline.analysis

import com.google.common.truth.Truth.assertThat
import io.cosgriff.sightline.protocol.fake.SignalPattern
import org.junit.Test

class DipDetectorTest {
    private val detector = DipDetector(Thresholds.DEFAULT)
    private val events = mutableListOf<SignalEvent>()

    private fun good(snq: Int = 85) = SignalSample(true, 80, snq, 100)
    private fun bad(snq: Int = 50, seq: Int = 100) = SignalSample(true, 80, snq, seq)
    private val noLock = SignalSample(false, 30, 0, 0)

    /** One reading a second from [fromS], on channel 9.1. */
    private fun feed(fromS: Long, vararg samples: SignalSample) {
        samples.forEachIndexed { i, s -> events += detector.reading((fromS + i) * 1000, "9.1", s) }
    }

    @Test
    fun `one bad reading is not a dip, two are`() {
        feed(0, good(), bad(), good(), good(), good(), good(), good(), good(), good(), good(), good(), good())
        assertThat(detector.open).isEmpty()
        feed(12, bad(), bad(55))
        assertThat(detector.open.single().kind).isEqualTo(EventKind.DIP)
        assertThat(detector.open.single().startMs).isEqualTo(12_000)
    }

    @Test
    fun `seq under 100 is bad even with good snq`() {
        feed(0, bad(snq = 85, seq = 99), bad(snq = 85, seq = 97))
        assertThat(detector.open.single().minSeq).isEqualTo(97)
        assertThat(detector.open.single().minSnq).isEqualTo(85)
    }

    @Test
    fun `five good readings end a dip at the first of them, and it is final after the merge window`() {
        feed(0, bad(52), bad(40, 90), bad(58), good(), good(), good(), good(), good())
        assertThat(events).isEmpty() // waiting out the merge window
        feed(8, good(), good(), good(), good(), good(), good(), good())
        assertThat(events).containsExactly(SignalEvent(EventKind.DIP, "9.1", 0, 3_000, minSnq = 40, minSeq = 90))
    }

    @Test
    fun `a bad reading during recovery starts the count again`() {
        feed(0, bad(), bad(), good(), good(), good(), good(), bad(), good(), good(), good(), good(), good())
        events += detector.finish(20_000)
        assertThat(events.single().endMs).isEqualTo(7_000)
    }

    @Test
    fun `no lock starts a dropout at once and its time without lock is measured`() {
        feed(0, good(), noLock, noLock, noLock, bad(45), good(), good(), good(), good(), good())
        events += detector.finish(30_000)
        val e = events.single()
        assertThat(e.kind).isEqualTo(EventKind.DROPOUT)
        assertThat(e.startMs).isEqualTo(1_000)
        assertThat(e.endMs).isEqualTo(5_000)
        assertThat(e.noLockMs).isEqualTo(3_000)
        assertThat(e.minSnq).isEqualTo(45)
    }

    @Test
    fun `a dropout that never relocked has no lowest values`() {
        feed(0, noLock, noLock, good(), good(), good(), good(), good())
        events += detector.finish(30_000)
        assertThat(events.single().minSnq).isNull()
        assertThat(events.single().noLockMs).isEqualTo(2_000)
    }

    @Test
    fun `dips less than 10 s apart merge, and the merged one is a dropout if either was`() {
        // Dip 0..2, recovers at 2; a dropout starts at 9, 7 s later.
        feed(0, bad(50), bad(48), good(), good(), good(), good(), good(), good(), good(), noLock, good(), good(), good(), good(), good())
        events += detector.finish(40_000)
        val e = events.single()
        assertThat(e.kind).isEqualTo(EventKind.DROPOUT)
        assertThat(e.startMs).isEqualTo(0)
        assertThat(e.endMs).isEqualTo(10_000)
        assertThat(e.minSnq).isEqualTo(48)
        assertThat(e.noLockMs).isEqualTo(1_000)
    }

    @Test
    fun `dips 10 s or more apart stay separate`() {
        feed(0, bad(), bad(), good(), good(), good(), good(), good(), good(), good(), good(), good(), good(), bad(), bad())
        events += detector.finish(20_000)
        assertThat(events.map { it.startMs }).containsExactly(0L, 12_000L).inOrder()
    }

    @Test
    fun `three failed polls are an unreachable device, closing the dip at its last reading`() {
        feed(0, good(), bad(), bad())
        events += detector.missedPoll(3_000)
        events += detector.missedPoll(4_000)
        assertThat(events).isEmpty()
        events += detector.missedPoll(5_000)
        assertThat(events.single()).isEqualTo(SignalEvent(EventKind.DIP, "9.1", 1_000, 2_000, minSnq = 50, minSeq = 100))
        assertThat(detector.open.single().kind).isEqualTo(EventKind.DEVICE_UNREACHABLE)
        feed(9, good())
        assertThat(events.last()).isEqualTo(SignalEvent(EventKind.DEVICE_UNREACHABLE, null, 3_000, 9_000))
    }

    @Test
    fun `two failed polls are nothing`() {
        feed(0, good())
        events += detector.missedPoll(1_000)
        events += detector.missedPoll(2_000)
        feed(3, good())
        events += detector.finish(4_000)
        assertThat(events).isEmpty()
    }

    @Test
    fun `a silence over 60 s is a gap, but not after a pause`() {
        feed(0, good())
        feed(70, good())
        assertThat(events.single()).isEqualTo(SignalEvent(EventKind.GAP, "9.1", 0, 70_000))
        events.clear()
        events += detector.pause(71_000)
        feed(200, good())
        assertThat(events).isEmpty()
    }

    @Test
    fun `a gap closes the dip in progress`() {
        feed(0, bad(), bad())
        feed(100, good())
        assertThat(events.map { it.kind }).containsExactly(EventKind.DIP, EventKind.GAP).inOrder()
        assertThat(events[0].endMs).isEqualTo(1_000)
    }

    @Test
    fun `a lost tuner lasts until the next reading and is never a gap`() {
        feed(0, good())
        events += detector.tunerLost(1_000, "9.1")
        assertThat(detector.open.single().kind).isEqualTo(EventKind.TUNER_LOST)
        feed(600, good())
        assertThat(events).containsExactly(SignalEvent(EventKind.TUNER_LOST, "9.1", 1_000, 600_000))
    }

    @Test
    fun `finish closes outages at the end time`() {
        feed(0, good())
        events += detector.tunerLost(1_000, "9.1")
        events += detector.finish(50_000)
        assertThat(events).containsExactly(SignalEvent(EventKind.TUNER_LOST, "9.1", 1_000, 50_000))
    }

    @Test
    fun `changing channel closes the last channel's dip and never merges across channels`() {
        feed(0, bad(), bad())
        events += detector.reading(2_000, "4.1", bad())
        events += detector.reading(3_000, "4.1", bad())
        events += detector.finish(10_000)
        assertThat(events.map { it.guideNumber to it.startMs }).containsExactly("9.1" to 0L, "4.1" to 2_000L).inOrder()
    }

    @Test
    fun `an hour of the Demo dropout pattern is 30 dropouts of 6 s`() {
        val pattern = SignalPattern.dropouts()
        for (s in 0L until 3_600) events += detector.reading(s * 1000, "9.1", pattern.at(s * 1000).sample())
        events += detector.finish(3_600_000)
        assertThat(events).hasSize(30)
        assertThat(events.map { it.kind }.toSet()).containsExactly(EventKind.DROPOUT)
        // Each lasts from the first reading without lock to the first with it; the last one is cut off by the end.
        assertThat(events.dropLast(1).map { it.durationMs }.toSet()).containsExactly(6_000L)
        assertThat(events.dropLast(1).map { it.noLockMs }.toSet()).containsExactly(6_000L)
        assertThat(events.map { it.minSnq }.toSet()).containsExactly(null)
        assertThat(events.first().startMs).isEqualTo(114_000)
    }

    @Test
    fun `an hour of the Demo flutter pattern is one dip per gust`() {
        val pattern = SignalPattern.flutter()
        for (s in 0L until 3_600) events += detector.reading(s * 1000, "4.1", pattern.at(s * 1000).sample())
        events += detector.finish(3_600_000)
        // 45 s gusts: 80 of them in an hour, each dipping under 60 near its bottom.
        assertThat(events).hasSize(80)
        assertThat(events.map { it.kind }.toSet()).containsExactly(EventKind.DIP)
        events.forEach {
            assertThat(it.minSnq).isAtMost(52)
            // About 8 s under 60; jitter near the edge can restart the recovery count once or twice.
            assertThat(it.durationMs).isIn(4_000L..20_000L)
        }
    }

    @Test
    fun `a steady hour has no events`() {
        val pattern = SignalPattern.steady()
        for (s in 0L until 3_600) events += detector.reading(s * 1000, "2.1", pattern.at(s * 1000).sample())
        events += detector.finish(3_600_000)
        assertThat(events).isEmpty()
    }

    @Test
    fun `counts and longest count dips and dropouts only`() {
        val list = listOf(
            SignalEvent(EventKind.DIP, "9.1", 0, 5_000),
            SignalEvent(EventKind.DROPOUT, "9.1", 10_000, 48_000),
            SignalEvent(EventKind.GAP, null, 50_000, 200_000),
        )
        assertThat(list.dipCount()).isEqualTo(2)
        assertThat(list.longestDipMs()).isEqualTo(38_000)
        assertThat(emptyList<SignalEvent>().longestDipMs()).isEqualTo(0)
    }

    private fun io.cosgriff.sightline.protocol.fake.Signal.sample() = SignalSample(locked, ss, snq, seq)
}
