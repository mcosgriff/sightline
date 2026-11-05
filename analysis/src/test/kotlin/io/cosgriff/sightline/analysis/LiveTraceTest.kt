package io.cosgriff.sightline.analysis

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LiveTraceTest {
    private fun at(ms: Long, snq: Int, locked: Boolean = true, ss: Int = 80, seq: Int = 100) =
        TimedSample(ms, SignalSample(locked, ss, snq, seq))

    @Test
    fun `keeps the last 60 s`() {
        var trace = LiveTrace()
        for (s in 0..90) trace += at(s * 1000L, 50 + s % 10)
        assertThat(trace.recent.first().atMs).isEqualTo(31_000)
        assertThat(trace.recent).hasSize(60)
        assertThat(trace.latest?.atMs).isEqualTo(90_000)
    }

    @Test
    fun `best is per metric and only while locked`() {
        val trace = LiveTrace() + at(0, 70, ss = 90, seq = 95) + at(1000, 85, ss = 60) + at(2000, 0, locked = false, ss = 100)
        assertThat(trace.best).isEqualTo(Best(ss = 90, snq = 85, seq = 100))
    }

    @Test
    fun `no best before a lock`() {
        assertThat((LiveTrace() + at(0, 0, locked = false)).best).isNull()
    }

    @Test
    fun `a clock jump backwards drops samples from the future`() {
        val trace = LiveTrace() + at(10_000, 80) + at(11_000, 80) + at(5_000, 80)
        assertThat(trace.recent.map { it.atMs }).containsExactly(5_000L)
    }
}
