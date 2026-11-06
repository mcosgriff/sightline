package io.cosgriff.sightline.data.device

import com.google.common.truth.Truth.assertThat
import io.cosgriff.sightline.protocol.ControlResult
import io.cosgriff.sightline.protocol.Probe
import io.cosgriff.sightline.protocol.TunerSnapshot
import io.cosgriff.sightline.protocol.UnreachableReason
import org.junit.Test

class TunerHolderTest {
    private val phone = "192.168.1.40"

    private fun tuner(status: String?, lockkey: String = "none", target: String = "none") = TunerSnapshot(
        0,
        listOf(
            Probe("/tuner0/status", status?.let { ControlResult.Value(it) } ?: ControlResult.Unreachable(UnreachableReason.TIMED_OUT)),
            Probe("/tuner0/lockkey", ControlResult.Value(lockkey)),
            Probe("/tuner0/target", ControlResult.Value(target)),
        ),
    )

    private val tuned = "ch=8vsb:189000000 lock=8vsb ss=100 snq=97 seq=100 bps=1 pps=0"
    private val idle = "ch=none lock=none ss=0 snq=0 seq=0 bps=0 pps=0"

    private fun of(t: TunerSnapshot, ours: Boolean = false, key: Boolean = false) = DeviceDiagnostics.holderOf(t, phone, ours, key)

    @Test
    fun `idle, ours, and other clients`() {
        assertThat(of(tuner(idle))).isEqualTo(TunerHolder.Idle)
        assertThat(of(tuner(tuned, lockkey = phone), ours = true)).isEqualTo(TunerHolder.ThisApp)
        assertThat(of(tuner(tuned, lockkey = "192.168.1.21"))).isEqualTo(TunerHolder.Other("192.168.1.21"))
    }

    @Test
    fun `this phone holding a tuner the app doesn't is a leftover`() {
        assertThat(of(tuner(tuned, lockkey = phone), key = true)).isEqualTo(TunerHolder.ThisPhone(staleKey = true))
        // An ATSC 3.0 stream from this phone: no key to release.
        assertThat(of(tuner(tuned, target = "http://$phone:41234"))).isEqualTo(TunerHolder.ThisPhone(staleKey = false))
    }

    @Test
    fun `tuned with nobody holding it, and no answer`() {
        assertThat(of(tuner(tuned))).isEqualTo(TunerHolder.TunedNoHolder)
        assertThat(of(tuner(null))).isEqualTo(TunerHolder.Unknown)
    }
}
