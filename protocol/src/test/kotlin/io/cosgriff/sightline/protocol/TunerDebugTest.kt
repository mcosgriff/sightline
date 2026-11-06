package io.cosgriff.sightline.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TunerDebugTest {
    private fun captured() = Captures.controlFiles.flatMap { Captures.packets(it) }
        .filter { it.fromDevice }
        .map { decodeOk(it.bytes) }
        .filter { it[Tag.GETSET_NAME]?.string()?.endsWith("/debug") == true && it[Tag.GETSET_VALUE] != null }
        .map { it.valueOrError() }

    @Test
    fun `every captured debug reply parses`() {
        val replies = captured()
        assertThat(replies.size).isAtLeast(3)
        for (r in replies) assertThat(TunerDebug.parse(r)?.transportErrors).isNotNull()
    }

    @Test
    fun `a tuned reply gives the counters`() {
        val tuned = captured().mapNotNull { TunerDebug.parse(it) }.first { it.ss == 100 }
        assertThat(tuned.snq).isEqualTo(97)
        assertThat(tuned.seq).isEqualTo(100)
        assertThat(tuned.dbg).isEqualTo("10-136/558")
        assertThat(tuned.deviceBps).isEqualTo(19_636_224)
        assertThat(tuned.resync).isEqualTo(0)
        assertThat(tuned.overflow).isEqualTo(0)
        assertThat(tuned.transportBps).isEqualTo(8_335_168)
        assertThat(tuned.transportErrors).isEqualTo(0)
        assertThat(tuned.crcErrors).isEqualTo(0)
        assertThat(tuned.networkErrors).isEqualTo(0)
        assertThat(tuned.networkStops).isEqualTo(0)
    }

    @Test
    fun `missing keys are null, unknown ones ignored, garbage is null`() {
        val d = TunerDebug.parse("ts: te=12 extra=1\nnew: thing=2\n")!!
        assertThat(d.transportErrors).isEqualTo(12)
        assertThat(d.crcErrors).isNull()
        assertThat(d.ss).isNull()
        assertThat(TunerDebug.parse("")).isNull()
        assertThat(TunerDebug.parse("no sections here")).isNull()
    }
}
