package io.cosgriff.sightline.data.device

import io.cosgriff.sightline.analysis.WeatherHour

/**
 * The Demo tuners, provided by debug builds only (two `FakeHdhr`s, in `:app`'s `src/debug`). Release builds have no
 * binding, so nothing of them exists there. Every screen works against either, so nothing needs hardware to build
 * or test, and there are two so the two-device parts of Monitor can be tried.
 */
interface DemoDevices {
    /** Every Demo tuner, in the order Devices lists them. */
    val devices: List<Device>

    fun isDemo(deviceId: String): Boolean = devices.any { it.deviceId == deviceId }

    /**
     * Made-up weather for a Demo session from [startMs] to [endMs], one entry per clock hour, matching the Demo
     * channels' scripted signals (the wind channel's gusts). Never fetched from Open-Meteo, and shown as Demo. Both
     * Demo tuners share it, as two units at one home do.
     */
    fun weather(startMs: Long, endMs: Long): List<WeatherHour> = emptyList()

    /**
     * Where the Demo tuner [deviceId] serves its made-up firmware files, in SiliconDust's layout. Never SiliconDust;
     * null when [deviceId] isn't a Demo tuner.
     */
    fun firmwareBaseUrl(deviceId: String): String? = null

    /**
     * Ends the made-up player's hold on Demo tuner [deviceId], so Device tools (refused while any tuner is in use)
     * can be tried. It comes back when the app restarts.
     */
    fun stopDemoPlayer(deviceId: String) {}
}
