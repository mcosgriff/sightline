package io.cosgriff.sightline.ui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** The HDHomeRun units on the network, and adding one by address. */
@Serializable
data object Devices : NavKey

/** Live gauges for one tuner and channel. Aim and Sweep open from here. */
@Serializable
data object Live : NavKey

/** Setting up and watching an unattended monitor session. */
@Serializable
data object Monitor : NavKey

/** Saved sessions, newest first. */
@Serializable
data object History : NavKey
