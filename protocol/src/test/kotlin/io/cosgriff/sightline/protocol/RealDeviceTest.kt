package io.cosgriff.sightline.protocol

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Against a real HDHomeRun on this machine's network, skipped unless `SIGHTLINE_REAL_HDHR` names its address:
 *
 *     SIGHTLINE_REAL_HDHR=192.168.1.18 ./gradlew :protocol:test --tests '*RealDeviceTest*'
 *
 * Claims only idle tuners and releases them, like the app. `SIGHTLINE_REAL_VCHANNEL` (default 9.1) and
 * `SIGHTLINE_REAL_ATSC3` (default 102.1, empty to skip) pick the channels.
 */
class RealDeviceTest {
    private val address = System.getenv("SIGHTLINE_REAL_HDHR")

    @Test
    fun `discover, claim, poll and release on a real unit`() = runBlocking<Unit> {
        assumeTrue("set SIGHTLINE_REAL_HDHR to run", !address.isNullOrBlank())
        val ip = InetAddress.getByName(address)

        val all = Discovery(PlainDiscoveryEnvironment()).discover()
        println("broadcast discover: ${all.map { "${it.deviceId}@${it.address.hostAddress}" }}")
        val probed = Discovery(PlainDiscoveryEnvironment(), includeGlobalBroadcast = false).probe(ip)
        println("probe: $probed")

        // UDP can be blocked where TCP isn't (a Mac's local network privacy, a guest Wi-Fi), so the rest goes on
        // from discover.json, the same fallback the app uses for a typed address.
        val http = HttpApi(OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build())
        val info = (http.deviceInfo("http://$address") as HttpResult.Ok).value
        println("discover.json: ${info.modelNumber} ${info.firmwareVersion} tuners=${info.tunerCount}")
        val device = probed ?: DiscoveredDevice(info.deviceId, info.tunerCount, info.baseUrl!!, info.lineupUrl, ip)
        val lineup = (http.lineup(device.lineupUrl!!) as HttpResult.Ok).value
        println("lineup: ${lineup.size} channels")

        val control = ControlClient(ip)
        try {
            val sessions = TunerSessions(device.deviceId, device.tunerCount, control, http)
            for (t in 0 until device.tunerCount) println("tuner$t: ${sessions.occupancy(t)}")

            val channels = listOfNotNull(
                System.getenv("SIGHTLINE_REAL_VCHANNEL") ?: "9.1",
                (System.getenv("SIGHTLINE_REAL_ATSC3") ?: "102.1").ifBlank { null },
            )
            for (guide in channels) {
                val entry = lineup.first { it.guideNumber == guide }
                coroutineScope {
                    val result = sessions.withTuner(this, TuneTarget.Virtual(guide, entry.url)) { claimed ->
                        println("$guide: claimed tuner${claimed.tuner} via ${claimed.hold::class.simpleName}, ch=${claimed.channel}")
                        repeat(4) {
                            delay(1_000)
                            println("  ${claimed.poll()}")
                        }
                        claimed.tuner
                    }
                    println("$guide: $result")
                    if (result is WithTuner.Ran) {
                        delay(500)
                        assertThat(sessions.occupancy(result.value)).isEqualTo(Occupancy.Idle)
                    }
                }
            }
        } finally {
            control.close()
        }
    }
}
