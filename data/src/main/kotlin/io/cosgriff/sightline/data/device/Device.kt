package io.cosgriff.sightline.data.device

import io.cosgriff.sightline.data.db.ChannelEntity
import io.cosgriff.sightline.data.db.DeviceEntity
import io.cosgriff.sightline.protocol.Band
import io.cosgriff.sightline.protocol.TuneTarget

/** An HDHomeRun as the screens see it. */
data class Device(
    val deviceId: String,
    val friendlyName: String,
    val model: String,
    val firmware: String,
    val baseUrl: String,
    val lineupUrl: String?,
    val tunerCount: Int,
    val address: String,
    val controlPort: Int,
    val lastSeen: Long,
    /** The debug-only Demo tuner (`FakeHdhr`). Never present in release builds. */
    val demo: Boolean = false,
)

/** A lineup channel. RF channel, frequency and band are known once it has been tuned. */
data class Channel(
    val deviceId: String,
    val guideNumber: String,
    val guideName: String,
    val streamUrl: String,
    val rfChannel: Int?,
    val frequencyHz: Long?,
    val band: Band?,
    val atsc3: Boolean,
) {
    val target get() = TuneTarget.Virtual(guideNumber, streamUrl)
}

/** Who has one tuner, as the Devices card shows it. */
sealed interface TunerUse {
    data object Idle : TunerUse

    /** Held by this app (Live, and later Monitor). */
    data object Sightline : TunerUse

    /** Held by another client. [holder] is its IP when the tuner says. */
    data class Other(val holder: String?) : TunerUse

    /** The tuner didn't answer. */
    data object Unknown : TunerUse
}

internal fun DeviceEntity.toDevice() = Device(
    deviceId, friendlyName, model, firmware, baseUrl, lineupUrl, tunerCount, address, controlPort, lastSeen,
)

internal fun ChannelEntity.toChannel() = Channel(
    deviceId, guideNumber, guideName, streamUrl, rfChannel, frequencyHz,
    band?.let { name -> Band.entries.firstOrNull { it.name == name } }, atsc3,
)
