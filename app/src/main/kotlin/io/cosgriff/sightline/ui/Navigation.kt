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

/** Debug builds only: raw protocol output from discovered tuners and the Demo tuner (milestone 1). */
@Serializable
data object ProtocolDebug : NavKey

/** Aiming with one giant number and the compass plot. Opens from Live. */
@Serializable
data object Aim : NavKey

/** Stepping through every channel, then the results and comparison. Opens from Live. */
@Serializable
data object Sweep : NavKey

/** One saved session analysed: charts, diagnosis and events. Opens from History or a finished Monitor. */
@Serializable
data class Session(val id: Long) : NavKey

/** Thresholds, units, theme and the rest. Opens from Live's top bar. */
@Serializable
data object Settings : NavKey

/** Every tuner of Live's device at once, each on its own channel. Opens from Live's top bar. */
@Serializable
data object MultiTuner : NavKey

/** Two saved sessions side by side. Opens from History with two selected. */
@Serializable
data class Compare(val a: Long, val b: Long) : NavKey
