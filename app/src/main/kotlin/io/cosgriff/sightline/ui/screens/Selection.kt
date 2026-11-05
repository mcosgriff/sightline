package io.cosgriff.sightline.ui.screens

import io.cosgriff.sightline.data.device.Channel
import io.cosgriff.sightline.data.device.Device
import io.cosgriff.sightline.data.device.DeviceRepository
import io.cosgriff.sightline.data.settings.AppStateRepository
import kotlinx.coroutines.flow.first

/** The device and channel Live has selected, which Aim and Sweep work on. */
sealed interface Selection {
    /** Nothing picked on Devices yet, or the device is gone. */
    data object NoDevice : Selection

    data class NoChannels(val device: Device) : Selection

    data class Ready(val device: Device, val channels: List<Channel>, val channel: Channel) : Selection
}

/**
 * Reads Live's selection with a fresh lineup (stream URLs carry the device's address, which can change), falling
 * back to the stored lineup when the device doesn't answer.
 */
suspend fun loadSelection(devices: DeviceRepository, appState: AppStateRepository): Selection {
    val selection = appState.liveSelection.first()
    val device = selection?.let { devices.device(it.deviceId) } ?: return Selection.NoDevice
    devices.refreshLineup(device)
    val channels = devices.channelList(device.deviceId)
    if (channels.isEmpty()) return Selection.NoChannels(device)
    val channel = channels.firstOrNull { it.guideNumber == selection.guideNumber } ?: channels.first()
    return Selection.Ready(device, channels, channel)
}
