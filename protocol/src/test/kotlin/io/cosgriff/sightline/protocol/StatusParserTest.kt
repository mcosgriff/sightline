package io.cosgriff.sightline.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StatusParserTest {
    private fun parsed(line: String) = (StatusParser.parse(line) as StatusParse.Parsed).status

    @Test
    fun `8vsb locked`() {
        val s = parsed("ch=8vsb:189000000 lock=8vsb ss=100 snq=97 seq=100 bps=7831328 pps=0")
        assertThat(s.channel).isEqualTo(TunedChannel.Frequency(Modulation.VSB8, 189_000_000))
        assertThat(s.lock).isEqualTo(Lock.Locked(Modulation.VSB8, "8vsb"))
        assertThat(listOf(s.ss, s.snq, s.seq)).containsExactly(100, 97, 100).inOrder()
        assertThat(s.bps).isEqualTo(7_831_328)
        assertThat(s.pps).isEqualTo(0)
        assertThat(s.isIdle).isFalse()
        assertThat(s.isLocked).isTrue()
    }

    @Test
    fun `atsc3 keeps the third ch field and reports bps 0`() {
        val s = parsed("ch=atsc3:593000000:1 lock=atsc3 ss=100 snq=100 seq=100 bps=0 pps=285")
        assertThat(s.channel).isEqualTo(TunedChannel.Frequency(Modulation.ATSC3, 593_000_000, "1"))
        assertThat(s.lock).isEqualTo(Lock.Locked(Modulation.ATSC3, "atsc3"))
        assertThat(s.bps).isEqualTo(0)
        assertThat(s.pps).isEqualTo(285)
    }

    @Test
    fun `idle`() {
        val s = parsed("ch=none lock=none ss=0 snq=0 seq=0 bps=0 pps=0")
        assertThat(s.isIdle).isTrue()
        assertThat(s.lock).isEqualTo(Lock.None)
    }

    @Test
    fun `tuned by rf with no lock still reports strength`() {
        val s = parsed("ch=auto:34 lock=none ss=100 snq=0 seq=0 bps=0 pps=0")
        assertThat(s.channel).isEqualTo(TunedChannel.Auto(34))
        assertThat(s.isLocked).isFalse()
        assertThat(s.ss).isEqualTo(100)
    }

    @Test
    fun `unknown keys, odd spacing and a trailing null are fine`() {
        val s = parsed("  ch=8vsb:485000000   lock=8vsb ss=81 snq=63 seq=100 bps=6172416 pps=0 dbg=10-136/558 new=1\u0000")
        assertThat(s.snq).isEqualTo(63)
        assertThat(s.raw).isEqualTo("ch=8vsb:485000000   lock=8vsb ss=81 snq=63 seq=100 bps=6172416 pps=0 dbg=10-136/558 new=1")
    }

    @Test
    fun `keys in another order`() {
        assertThat(parsed("snq=50 seq=90 ss=70 lock=qam256 ch=qam256:603000000").lock)
            .isEqualTo(Lock.Locked(Modulation.QAM256, "qam256"))
    }

    @Test
    fun `missing bps and pps are null, not zero`() {
        val s = parsed("ch=none lock=none ss=0 snq=0 seq=0")
        assertThat(s.bps).isNull()
        assertThat(s.pps).isNull()
    }

    @Test
    fun `an unknown modulation is kept raw`() {
        val s = parsed("ch=t8qam64:500000000 lock=t8qam64 ss=50 snq=50 seq=100")
        assertThat(s.lock).isEqualTo(Lock.Locked(Modulation.OTHER, "t8qam64"))
        assertThat(s.channel).isEqualTo(TunedChannel.Frequency(Modulation.OTHER, 500_000_000))
    }

    @Test
    fun `the debug form of lock drops the frequency`() {
        assertThat(StatusParser.parseLock("8vsb:189000000")).isEqualTo(Lock.Locked(Modulation.VSB8, "8vsb:189000000"))
    }

    @Test
    fun `odd channel forms are unknown, not guessed`() {
        assertThat(StatusParser.parseChannel("auto:x")).isEqualTo(TunedChannel.Unknown("auto:x"))
        assertThat(StatusParser.parseChannel("weird")).isEqualTo(TunedChannel.Unknown("weird"))
        assertThat(StatusParser.parseChannel("8vsb:abc")).isEqualTo(TunedChannel.Unknown("8vsb:abc"))
    }

    @Test
    fun `lines without a required key or with a bad number are unparseable, raw kept`() {
        for (line in listOf(
            "",
            "ERROR: unknown getset variable",
            "ch=none lock=none ss=0 snq=0",
            "lock=none ss=0 snq=0 seq=0",
            "ch=none lock=none ss=x snq=0 seq=0",
            "ch=none lock=none ss=0 snq=0 seq=0 bps=lots",
        )) {
            assertThat(StatusParser.parse(line)).isEqualTo(StatusParse.Unparseable(line))
        }
    }
}
