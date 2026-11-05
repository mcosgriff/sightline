package io.cosgriff.sightline.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** An HDHomeRun Sightline has seen (SPEC "Data model"). Refreshed on each discovery. */
@Entity(tableName = "device")
data class DeviceEntity(
    @PrimaryKey val deviceId: String,
    val friendlyName: String,
    val model: String,
    val firmware: String,
    val baseUrl: String,
    val lineupUrl: String?,
    val tunerCount: Int,
    /** The control protocol address, an IP. */
    val address: String,
    val controlPort: Int,
    /** UTC epoch ms of the last time it answered. */
    val lastSeen: Long,
    /** What the user typed to add it, when added by address rather than found by discovery. */
    val addedAs: String?,
)

/** One lineup entry. [rfChannel], [frequencyHz] and [band] are filled the first time it is tuned. */
@Entity(tableName = "channel", primaryKeys = ["deviceId", "guideNumber"])
data class ChannelEntity(
    val deviceId: String,
    val guideNumber: String,
    val guideName: String,
    /** The lineup entry's URL, `http://<ip>:5004/auto/v<guide>`; the stream for ATSC 3.0 (ADR-0004). */
    val streamUrl: String,
    /** Position in the device's lineup, for stepping through channels in its order. */
    val position: Int,
    val rfChannel: Int? = null,
    val frequencyHz: Long? = null,
    /** `Band` name from `:protocol`. */
    val band: String? = null,
    /** True once seen locked as ATSC 3.0. */
    val atsc3: Boolean = false,
    val favorite: Boolean = false,
)
