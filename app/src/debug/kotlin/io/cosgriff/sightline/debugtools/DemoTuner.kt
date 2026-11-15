package io.cosgriff.sightline.debugtools

import io.cosgriff.sightline.protocol.DiscoveredDevice
import io.cosgriff.sightline.protocol.fake.FakeHdhr
import io.cosgriff.sightline.protocol.fake.UnitFault
import java.net.InetAddress

/**
 * A Demo tuner: [FakeHdhr] on loopback, started on first use and kept for the life of the process, so every screen
 * can be built and tried without hardware. There are two ([all]), as the user has two units, so the two-device
 * parts of Monitor can be tried too. Debug builds only; the release build has no fake at all.
 */
class DemoTuner private constructor(private val playerTuner: Int, start: () -> FakeHdhr) {
    /**
     * Each has a tuner held by a made-up player, so watch mode and the network side of packet errors can be tried
     * (SPEC "Testing", Demo device). Device tools can stop it ([stopPlayer]).
     */
    private val fake by lazy(start)

    /** The firmware this Demo tuner runs now; a Device tools update changes it until the app restarts. */
    val firmware: String get() = fake.firmwareVersion

    /** `FriendlyName`, as discover.json gives it. */
    val friendlyName: String get() = fake.friendlyName

    /** It serves made-up firmware files itself, in SiliconDust's layout, so an update never leaves the phone. */
    val firmwareBaseUrl: String get() = fake.baseUrl

    /** Stops the made-up player, so Device tools can be tried. */
    fun stopPlayer() = fake.freeTuner(playerTuner)

    /** This Demo tuner as discovery would have found it. Its ID can't belong to a real unit's range. */
    val device: DiscoveredDevice
        get() = DiscoveredDevice(
            deviceId = fake.deviceIdHex,
            tunerCount = fake.tunerCount,
            baseUrl = fake.baseUrl,
            lineupUrl = "${fake.baseUrl}/lineup.json",
            address = InetAddress.getByName(fake.host),
            controlPort = fake.controlPort,
        )

    companion object {
        /** The first Demo tuner: the made-up player on tuner 3, an update on offer ([FakeHdhr.demo]). */
        val first = DemoTuner(FakeHdhr.DEMO_PLAYER_TUNER) { FakeHdhr.demo() }

        /**
         * The second: weaker signal, the player on tuner 0, firmware up to date ([FakeHdhr.secondDemo]), and a Demo
         * fault of its own ([DEMO_FAULT]), so a two-unit compare of the two shows the One unit finding.
         */
        val second = DemoTuner(FakeHdhr.SECOND_DEMO_PLAYER_TUNER) { FakeHdhr.secondDemo().apply { fault = DEMO_FAULT } }

        /**
         * Demo tuner 2's made-up coax fault: every tuner on it loses lock for 20 s every 15 minutes of clock time,
         * while "Demo tuner" carries on. Demo only (SPEC "Testing", Demo devices).
         */
        val DEMO_FAULT: UnitFault = UnitFault.recurring(everyMs = 15 * 60_000L, lengthMs = 20_000L)

        /** Both, in the order Devices lists them. */
        val all: List<DemoTuner> = listOf(first, second)

        fun byId(deviceId: String): DemoTuner? = all.firstOrNull { it.device.deviceId == deviceId }
    }
}
