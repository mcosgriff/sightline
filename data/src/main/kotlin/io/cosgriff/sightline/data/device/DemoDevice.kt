package io.cosgriff.sightline.data.device

import io.cosgriff.sightline.analysis.WeatherHour

/**
 * The Demo tuner, provided by debug builds only (`FakeHdhr`, in `:app`'s `src/debug`). Release builds have no
 * binding, so nothing of it exists there. Every screen works against it, so nothing needs hardware to build or
 * test.
 */
interface DemoDevice {
    val device: Device

    /**
     * Made-up weather for a Demo session from [startMs] to [endMs], one entry per clock hour, matching the Demo
     * channels' scripted signals (the wind channel's gusts). Never fetched from Open-Meteo, and shown as Demo.
     */
    fun weather(startMs: Long, endMs: Long): List<WeatherHour> = emptyList()

    /** Where the Demo tuner serves its made-up firmware files, in SiliconDust's layout. Never SiliconDust. */
    val firmwareBaseUrl: String? get() = null

    /**
     * Ends the made-up player's hold on a Demo tuner, so Device tools (refused while any tuner is in use) can be
     * tried. It comes back when the app restarts.
     */
    fun stopDemoPlayer() {}
}
