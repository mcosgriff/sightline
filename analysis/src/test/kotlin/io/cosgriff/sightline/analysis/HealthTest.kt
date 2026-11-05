package io.cosgriff.sightline.analysis

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HealthTest {
    private val t = Thresholds.DEFAULT

    private fun assess(ss: Int, snq: Int, seq: Int, locked: Boolean = true) = t.assess(SignalSample(locked, ss, snq, seq))

    @Test
    fun `snq bands at their edges`() {
        assertThat(listOf(100, 80, 79, 60, 59, 0).map(t::snq)).containsExactly(
            Health.GOOD, Health.GOOD, Health.MARGINAL, Health.MARGINAL, Health.POOR, Health.POOR,
        ).inOrder()
    }

    @Test
    fun `seq bands, anything under 100 is at best marginal`() {
        assertThat(listOf(100, 99, 90, 89).map(t::seq))
            .containsExactly(Health.GOOD, Health.MARGINAL, Health.MARGINAL, Health.POOR).inOrder()
    }

    @Test
    fun `ss bands, with overload marginal`() {
        assertThat(listOf(24, 25, 39, 40, 94, 95, 100).map(t::ss)).containsExactly(
            Health.POOR, Health.MARGINAL, Health.MARGINAL, Health.GOOD, Health.GOOD, Health.MARGINAL, Health.MARGINAL,
        ).inOrder()
    }

    @Test
    fun `clean signal`() {
        assertThat(assess(88, 86, 100)).isEqualTo(Assessment(Health.GOOD, HealthReason.CLEAN))
    }

    @Test
    fun `good but at the overload line`() {
        assertThat(assess(100, 97, 100)).isEqualTo(Assessment(Health.GOOD, HealthReason.GOOD_NEAR_OVERLOAD))
    }

    @Test
    fun `strong but low quality is overload`() {
        assertThat(assess(98, 55, 100)).isEqualTo(Assessment(Health.POOR, HealthReason.OVERLOAD))
    }

    @Test
    fun `weak strength explains low quality`() {
        assertThat(assess(30, 62, 100)).isEqualTo(Assessment(Health.MARGINAL, HealthReason.WEAK))
    }

    @Test
    fun `good quality with lost symbols`() {
        assertThat(assess(81, 85, 96)).isEqualTo(Assessment(Health.MARGINAL, HealthReason.SYMBOL_ERRORS))
        assertThat(assess(81, 85, 40)).isEqualTo(Assessment(Health.POOR, HealthReason.SYMBOL_ERRORS))
    }

    @Test
    fun `enough strength but poor quality`() {
        assertThat(assess(81, 63, 100)).isEqualTo(Assessment(Health.MARGINAL, HealthReason.LOW_QUALITY))
    }

    @Test
    fun `the worse of snq and seq wins`() {
        assertThat(assess(81, 70, 50).health).isEqualTo(Health.POOR)
    }

    @Test
    fun `no lock ignores the numbers`() {
        assertThat(assess(100, 0, 0, locked = false)).isEqualTo(Assessment(Health.NO_LOCK, HealthReason.NO_LOCK))
    }

    @Test
    fun `custom thresholds move the bands`() {
        val strict = Thresholds(snqGood = 90)
        assertThat(strict.assess(SignalSample(true, 80, 85, 100)).health).isEqualTo(Health.MARGINAL)
    }
}
