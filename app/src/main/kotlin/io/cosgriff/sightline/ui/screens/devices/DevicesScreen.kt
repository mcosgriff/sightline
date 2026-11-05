package io.cosgriff.sightline.ui.screens.devices

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.cosgriff.sightline.R
import io.cosgriff.sightline.ui.components.NotBuiltYet

/** Not built yet (milestone 2). [onOpenProtocolDebug] is set in debug builds only. */
@Composable
fun DevicesScreen(onOpenProtocolDebug: (() -> Unit)? = null) {
    Column {
        NotBuiltYet(
            stringResource(R.string.devices_title),
            stringResource(R.string.devices_not_built),
            modifier = Modifier.weight(1f, fill = false),
        )
        if (onOpenProtocolDebug != null) {
            OutlinedButton(onClick = onOpenProtocolDebug, modifier = Modifier.padding(start = 20.dp, top = 8.dp)) {
                Text(stringResource(R.string.devices_open_protocol_debug))
            }
        }
    }
}
