package io.cosgriff.sightline.debugtools

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.cosgriff.sightline.analysis.WeatherHour
import io.cosgriff.sightline.data.device.DemoDevice
import io.cosgriff.sightline.data.device.Device
import io.cosgriff.sightline.protocol.fake.Wind

/** Debug builds list the Demo tuner on Devices. Release builds have no such module, so no Demo tuner. */
@Module
@InstallIn(SingletonComponent::class)
object DemoModule {
    @Provides
    fun demoDevice(): DemoDevice = object : DemoDevice {
        override val device: Device
            get() = DemoTuner.device.let {
                Device(
                    deviceId = it.deviceId,
                    friendlyName = "Demo tuner",
                    model = "HDFX-4K",
                    firmware = "20260326",
                    baseUrl = it.baseUrl,
                    lineupUrl = it.lineupUrl,
                    tunerCount = it.tunerCount,
                    address = it.address.hostAddress ?: "127.0.0.1",
                    controlPort = it.controlPort,
                    lastSeen = 0,
                    demo = true,
                )
            }

        /**
         * Gusts from the Demo wind channel's schedule, taking the session start as its tuning time (true for a
         * session held on that channel), so its dips follow the wind. Mild, dry and 12 °C throughout.
         */
        override fun weather(startMs: Long, endMs: Long): List<WeatherHour> {
            val first = Math.floorDiv(startMs, WeatherHour.HOUR_MS) * WeatherHour.HOUR_MS
            return (first..endMs step WeatherHour.HOUR_MS).map { hour ->
                val gust = Wind.gustKphAt(hour + WeatherHour.HOUR_MS / 2 - startMs)
                WeatherHour(hour, tempC = 12.0, windKph = gust / 2, gustKph = gust, precipMm = 0.0)
            }
        }
    }
}
