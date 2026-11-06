package io.cosgriff.sightline.ui.screens.live

import com.google.common.truth.Truth.assertThat
import io.cosgriff.sightline.data.device.Channel
import org.junit.Test

class ChannelMatchingTest {
    private fun ch(number: String, name: String) = Channel("D", number, name, "http://x/auto/v$number", null, null, null, false)

    private val lineup = listOf(ch("2.1", "KTWO"), ch("7.1", "Seven"), ch("7.2", "Seven Too"), ch("17.1", "Channel 7 Kids"), ch("70.1", "Retro"))

    private fun find(q: String) = lineup.matching(q).map { it.guideNumber }

    @Test
    fun `blank keeps the lineup in order`() {
        assertThat(find("  ")).isEqualTo(lineup.map { it.guideNumber })
    }

    @Test
    fun `numbers match by prefix, then names that mention it`() {
        assertThat(find("7")).containsExactly("7.1", "7.2", "70.1", "17.1").inOrder()
        assertThat(find("7.1")).containsExactly("7.1")
    }

    @Test
    fun `a dash or space stands for the dot`() {
        assertThat(find("7-2")).containsExactly("7.2")
        assertThat(find("7 2")).containsExactly("7.2")
    }

    @Test
    fun `names match anywhere, any case`() {
        assertThat(find("seven")).containsExactly("7.1", "7.2").inOrder()
        assertThat(find("kids")).containsExactly("17.1")
    }
}
