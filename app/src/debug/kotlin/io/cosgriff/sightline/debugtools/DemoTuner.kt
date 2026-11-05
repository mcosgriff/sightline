package io.cosgriff.sightline.debugtools

import io.cosgriff.sightline.protocol.DiscoveredDevice
import io.cosgriff.sightline.protocol.fake.FakeHdhr
import java.net.InetAddress

/**
 * The Demo tuner: [FakeHdhr] on loopback, started on first use and kept for the life of the process, so every
 * screen can be built and tried without hardware. Debug builds only; the release build has no fake at all.
 */
object DemoTuner {
    private val fake by lazy { FakeHdhr() }

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
