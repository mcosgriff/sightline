package io.cosgriff.sightline.analysis

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WifiTest {
    private fun on(at: Long, rssi: Int? = -60, mhz: Int? = 5_180, bssid: String? = "aa:bb:cc:00:00:01") =
        WifiSample(at, connected = true, rssiDbm = rssi, linkMbps = 433, frequencyMhz = mhz, bssid = bssid)

    private fun off(at: Long) = WifiSample(at, connected = false)

    @Test
    fun `bands from frequencies`() {
        assertThat(WifiBand.of(2_437)).isEqualTo(WifiBand.GHZ_2_4)
        assertThat(WifiBand.of(5_745)).isEqualTo(WifiBand.GHZ_5)
        assertThat(WifiBand.of(6_115)).isEqualTo(WifiBand.GHZ_6)
        assertThat(WifiBand.of(900)).isNull()
    }

    @Test
    fun `signal health by the good and weak lines`() {
        assertThat(WifiSignal.health(-67)).isEqualTo(Health.GOOD)
        assertThat(WifiSignal.health(-79)).isEqualTo(Health.MARGINAL)
        assertThat(WifiSignal.health(-80)).isEqualTo(Health.POOR)
    }

    @Test
    fun `a roam is a known BSSID changing on Wi-Fi, and coming back elsewhere after a drop is a disconnect`() {
        val samples = listOf(on(0), on(10, bssid = "aa:bb:cc:00:00:02"), off(20), on(30, bssid = "aa:bb:cc:00:00:01"), on(40, bssid = null), on(50))
        assertThat(roamsOf(samples)).containsExactly(10L)
        assertThat(disconnectsOf(samples)).containsExactly(20L)
        assertThat(disconnectsOf(listOf(off(0), off(5), on(9), off(12)))).containsExactly(0L, 12L).inOrder()
    }

    @Test
    fun `the summary gives the range, bands, roams, drops and access points`() {
        val s = WifiSummary.of(listOf(on(0, rssi = -48, mhz = 2_437), on(10, rssi = -83, bssid = "aa:bb:cc:00:00:02"), off(20), on(30, rssi = -60)))!!
        assertThat(s.minRssiDbm).isEqualTo(-83)
        assertThat(s.maxRssiDbm).isEqualTo(-48)
        assertThat(s.bands).containsExactly(WifiBand.GHZ_2_4, WifiBand.GHZ_5).inOrder()
        assertThat(s.roams).isEqualTo(1)
        assertThat(s.disconnects).isEqualTo(1)
        assertThat(s.accessPoints).containsExactly("aa:bb:cc:00:00:01", "aa:bb:cc:00:00:02").inOrder()
        assertThat(s.accessPointsUnknown).isFalse()
    }

    @Test
    fun `no BSSID ever known says so, and no samples is no summary`() {
        assertThat(WifiSummary.of(listOf(on(0, bssid = null)))!!.accessPointsUnknown).isTrue()
        assertThat(WifiSummary.of(emptyList())).isNull()
    }
}
