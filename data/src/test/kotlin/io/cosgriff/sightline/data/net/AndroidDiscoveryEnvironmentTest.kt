package io.cosgriff.sightline.data.net

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.net.InetAddress

class AndroidDiscoveryEnvironmentTest {
    private fun broadcast(ip: String, prefix: Int) =
        AndroidDiscoveryEnvironment.directedBroadcast(InetAddress.getByName(ip).address, prefix).hostAddress

    @Test
    fun `directed broadcast of common subnets`() {
        assertThat(broadcast("192.168.1.21", 24)).isEqualTo("192.168.1.255")
        assertThat(broadcast("10.0.5.9", 16)).isEqualTo("10.0.255.255")
        assertThat(broadcast("192.168.139.3", 23)).isEqualTo("192.168.139.255")
        assertThat(broadcast("172.16.4.200", 30)).isEqualTo("172.16.4.203")
    }
}
