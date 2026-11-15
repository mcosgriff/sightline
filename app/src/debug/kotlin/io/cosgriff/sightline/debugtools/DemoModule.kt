package io.cosgriff.sightline.debugtools

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.cosgriff.sightline.analysis.WeatherHour
import io.cosgriff.sightline.data.device.DemoDevices
import io.cosgriff.sightline.data.device.Device
import io.cosgriff.sightline.protocol.fake.Wind

/** Debug builds list the two Demo tuners on Devices. Release builds have no such module, so no Demo tuner. */
@Module
@InstallIn(SingletonComponent::class)
object DemoModule {
    @Provides
    fun demoDevices(): DemoDevices = DemoTunerDevices
}

/** The two [DemoTuner]s as Devices, Device tools and Session detail see them. */
internal object DemoTunerDevices : DemoDevices {
    override val devices: List<Device> get() = DemoTuner.all.map { it.asDevice() }

    override fun firmwareBaseUrl(deviceId: String): String? = DemoTuner.byId(deviceId)?.firmwareBaseUrl

    override fun stopDemoPlayer(deviceId: String) {
        DemoTuner.byId(deviceId)?.stopPlayer()
    }

    /**
     * Gusts from the Demo wind channel's schedule, taking the session start as its tuning time (true for a session
     * held on that channel), so its dips follow the wind. Mild, dry and 12 °C throughout.
     */
    override fun weather(startMs: Long, endMs: Long): List<WeatherHour> {
        val first = Math.floorDiv(startMs, WeatherHour.HOUR_MS) * WeatherHour.HOUR_MS
        return (first..endMs step WeatherHour.HOUR_MS).map { hour ->
            val gust = Wind.gustKphAt(hour + WeatherHour.HOUR_MS / 2 - startMs)
            WeatherHour(hour, tempC = 12.0, windKph = gust / 2, gustKph = gust, precipMm = 0.0)
        }
    }

    private fun DemoTuner.asDevice(): Device = device.let {
        Device(
            deviceId = it.deviceId,
            friendlyName = friendlyName,
            model = "HDFX-4K",
            firmware = firmware,
            baseUrl = it.baseUrl,
            lineupUrl = it.lineupUrl,
            tunerCount = it.tunerCount,
            address = it.address.hostAddress ?: "127.0.0.1",
            controlPort = it.controlPort,
            lastSeen = 0,
            demo = true,
        )
    }
}
