package io.cosgriff.sightline.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SysDebugTest {
    /** `get /sys/debug` from a FLEX 4K on firmware 20261107, 2026-11-14. */
    private val captured = "mem: ddr=256 nbk=3 dmk=377 fet=0\nloop: pkt=0\nt0: pt=12 cal=82\nt1: pt=12 cal=84\n" +
        "t2: pt=12 cal=80\nt3: pt=12 cal=81\neth: link=100f\n\u0000"

    @Test
    fun `the captured link is 100 Mbit full duplex, not limiting`() {
        val link = SysDebug.parse(captured).link!!
        assertThat(link).isEqualTo(EthernetLink(100, true, "100f"))
        assertThat(link.limiting).isFalse()
    }

    @Test
    fun `half duplex, 10 Mbit and no link limit the stream`() {
        assertThat(EthernetLink.parse("100h").limiting).isTrue()
        assertThat(EthernetLink.parse("10f").limiting).isTrue()
        assertThat(EthernetLink.parse("none").limiting).isTrue()
        assertThat(EthernetLink.parse("1000f").limiting).isFalse()
    }

    @Test
    fun `an unknown form is kept raw and not called slow`() {
        val link = EthernetLink.parse("auto")
        assertThat(link.speedMbps).isNull()
        assertThat(link.limiting).isFalse()
        assertThat(SysDebug.parse("mem: ddr=256").link).isNull()
    }
}
