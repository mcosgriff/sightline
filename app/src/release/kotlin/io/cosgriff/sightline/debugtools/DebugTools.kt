package io.cosgriff.sightline.debugtools

import androidx.compose.runtime.Composable

/** Release builds have no debug tools: the protocol debug screen and the Demo tuner exist only in debug builds. */
object DebugTools {
    const val AVAILABLE = false
}

@Composable
fun ProtocolDebugEntry() = Unit
