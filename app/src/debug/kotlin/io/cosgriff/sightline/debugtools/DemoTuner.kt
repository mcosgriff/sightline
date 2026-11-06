package io.cosgriff.sightline.debugtools

import io.cosgriff.sightline.protocol.DiscoveredDevice
import io.cosgriff.sightline.protocol.fake.FakeHdhr
import java.net.InetAddress

/**
 * The Demo tuner: [FakeHdhr] on loopback, started on first use and kept for the life of the process, so every
 * screen can be built and tried without hardware. Debug builds only; the release build has no fake at all.
 */
object DemoTuner {
    /**
     * Tuner 3 is held by a made-up player on the Wind channel whose stream loses packets on the network, so watch
     * mode and the network side of packet errors can be tried (SPEC "Testing", Demo device).
     */
    private val fake by lazy {
        FakeHdhr().apply { takeTuner(3, DEMO_PLAYER, channels.first { it.guideNumber == "5.1" }, networkLossPerSecond = 2) }
    }

    /** A documentation-range address (RFC 5737), so it can't be anyone's real player. */
    private const val DEMO_PLAYER = "192.0.2.20"

    /** The Demo tuner as discovery would have found it. Its ID can't belong to a real unit's range. */
    val device: DiscoveredDevice
        get() = DiscoveredDevice(
            deviceId = fake.deviceIdHex,
            tunerCount = fake.tunerCount,
            baseUrl = fake.baseUrl,
            lineupUrl = "${fake.baseUrl}/lineup.json",
            address = InetAddress.getByName(fake.host),
            controlPort = fake.controlPort,
        )
}
