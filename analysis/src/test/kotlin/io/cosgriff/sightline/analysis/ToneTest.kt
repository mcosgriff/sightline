package io.cosgriff.sightline.analysis

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ToneTest {
    @Test
    fun `pitch runs 200 to 1200 Hz with snq`() {
        assertThat(Tone.of(SignalSample(true, 80, 0, 100))!!.hz).isEqualTo(200.0)
        assertThat(Tone.of(SignalSample(true, 80, 50, 100))!!.hz).isEqualTo(700.0)
        assertThat(Tone.of(SignalSample(true, 80, 100, 100))!!.hz).isEqualTo(1200.0)
    }

    @Test
    fun `stutters below seq 100 and is silent without lock`() {
        assertThat(Tone.of(SignalSample(true, 80, 90, 100))!!.stutter).isFalse()
        assertThat(Tone.of(SignalSample(true, 80, 90, 99))!!.stutter).isTrue()
        assertThat(Tone.of(SignalSample(false, 30, 0, 0))).isNull()
    }
}
