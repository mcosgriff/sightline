package io.cosgriff.sightline.analysis

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AimTest {
    private fun locked(snq: Int, seq: Int = 100) = SignalSample(true, 80, snq, seq)
    private val noLock = SignalSample(false, 30, 0, 0)

    private fun AimTrace.feed(vararg readings: Pair<SignalSample, Double?>): Pair<AimTrace, List<AimEvent?>> {
        var trace = this
        val events = readings.map { (s, h) -> trace.add(s, h).also { trace = it.first }.second }
        return trace to events
    }

    @Test
    fun `the first lock sets the peak quietly, a higher one ticks, an equal one doesn't`() {
        val (trace, events) = AimTrace().feed(locked(70) to 10.0, locked(75) to 20.0, locked(75) to 30.0, locked(60) to 40.0)
        assertThat(events).containsExactly(null, AimEvent.NEW_PEAK, null, null).inOrder()
        assertThat(trace.peak).isEqualTo(AimPeak(75, 20))
    }

    @Test
    fun `losing lock buzzes once, and no-lock readings never move the peak or the plot`() {
        val (trace, events) = AimTrace().feed(locked(70) to 10.0, noLock to 12.0, noLock to 14.0, locked(65) to 16.0)
        assertThat(events).containsExactly(null, AimEvent.LOCK_LOST, null, null).inOrder()
        assertThat(trace.peak).isEqualTo(AimPeak(70, 10))
        assertThat(trace.points.map { it.snq }).containsExactly(70, 65).inOrder()
    }

    @Test
    fun `each five degree bin keeps its best and points come in compass order`() {
        val (trace, _) = AimTrace().feed(locked(60) to 352.0, locked(80) to 3.0, locked(70) to 4.9, locked(50) to 354.9)
        assertThat(trace.points).containsExactly(AimPoint(2.5, 80), AimPoint(352.5, 60)).inOrder()
    }

    @Test
    fun `headings wrap, and a peak without a compass has no heading`() {
        val (wrapped, _) = AimTrace().feed(locked(70) to -90.0)
        assertThat(wrapped.peak).isEqualTo(AimPeak(70, 270))
        val (blind, _) = AimTrace().feed(locked(70) to null)
        assertThat(blind.peak).isEqualTo(AimPeak(70, null))
        assertThat(blind.points).isEmpty()
    }

    @Test
    fun `reset forgets the peak and plot but still buzzes on a later loss`() {
        val (trace, _) = AimTrace().feed(locked(90) to 100.0)
        val (after, events) = trace.reset().feed(noLock to 0.0)
        assertThat(after.peak).isNull()
        assertThat(after.points).isEmpty()
        assertThat(events).containsExactly(AimEvent.LOCK_LOST)
    }
}
