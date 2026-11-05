package io.cosgriff.sightline.debugtools

import androidx.compose.runtime.Composable
import io.cosgriff.sightline.ui.screens.protocoldebug.ProtocolDebugScreen

/** Debug-build tools. The release source set has the same names with nothing behind them. */
object DebugTools {
    const val AVAILABLE = true
}

@Composable
fun ProtocolDebugEntry() = ProtocolDebugScreen()
