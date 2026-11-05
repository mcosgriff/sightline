package io.cosgriff.sightline.debugtools

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.cosgriff.sightline.data.device.DemoDevice
import io.cosgriff.sightline.data.device.Device

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
    }
}
