package io.cosgriff.sightline.data.live

import com.google.common.truth.Truth.assertThat
import io.cosgriff.sightline.data.device.Channel
import io.cosgriff.sightline.data.device.TunerUse
import org.junit.Test

class MultiTunerPlanTest {
    private fun ch(guide: String, rf: Int?, atsc3: Boolean = false) = Channel("D", guide, "", "", rf, null, null, atsc3)

    private val lineup = listOf(ch("2.1", 35), ch("2.2", 35), ch("4.1", 23), ch("7.1", 7), ch("9.1", 9), ch("9.2", 9, atsc3 = true), ch("14.1", null))
    private val idle = TunerUse.Idle

    @Test
    fun `every idle tuner but the last starts, each on another rf channel`() {
        val plan = MultiTunerPlan.initial(listOf(idle, idle, idle, idle), lineup, lineup[2])
        assertThat(plan.mapValues { it.value.guideNumber }).containsExactly(0, "4.1", 1, "7.1", 2, "9.1").inOrder()
    }

    @Test
    fun `tuners another client holds are left alone, and so is one idle tuner`() {
        val plan = MultiTunerPlan.initial(listOf(TunerUse.Other("10.0.0.5"), idle, TunerUse.Sightline, idle), lineup, lineup[0])
        assertThat(plan.keys).containsExactly(1, 2).inOrder()
        assertThat(plan.getValue(1).guideNumber).isEqualTo("2.1")
        // 2.2 shares RF 35 with 2.1.
        assertThat(plan.getValue(2).guideNumber).isEqualTo("4.1")
    }

    @Test
    fun `one idle tuner is kept free, so nothing starts`() {
        assertThat(MultiTunerPlan.initial(listOf(TunerUse.Other(null), TunerUse.Unknown, TunerUse.Other(null), idle), lineup, lineup[0])).isEmpty()
    }

    @Test
    fun `atsc 3 channels are skipped after the first, and the lineup wraps`() {
        val plan = MultiTunerPlan.initial(listOf(idle, idle, idle, idle), lineup, lineup[5])
        assertThat(plan.values.map { it.guideNumber }).containsExactly("9.2", "14.1", "2.1").inOrder()
    }

    @Test
    fun `a short lineup repeats channels`() {
        val plan = MultiTunerPlan.initial(listOf(idle, idle, idle), listOf(lineup[0]), lineup[0])
        assertThat(plan.values.map { it.guideNumber }).containsExactly("2.1", "2.1").inOrder()
    }
}
