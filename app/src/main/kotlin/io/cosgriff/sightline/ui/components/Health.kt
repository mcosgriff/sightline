package io.cosgriff.sightline.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.cosgriff.sightline.R
import io.cosgriff.sightline.analysis.Health
import io.cosgriff.sightline.ui.theme.sightlineColors

/** The health colour token. Never shown alone: always with [icon] or [label] (CLAUDE.md, colour is never the only signal). */
@Composable
@ReadOnlyComposable
fun Health.color(): Color = with(MaterialTheme.sightlineColors) {
    when (this@color) {
        Health.GOOD -> healthGood
        Health.MARGINAL -> healthMarginal
        Health.POOR -> healthPoor
        Health.NO_LOCK -> healthNoLock
    }
}

/** Check, warning, cross, dash (SPEC "Color tokens"). */
val Health.icon: ImageVector
    get() = when (this) {
        Health.GOOD -> Icons.Rounded.Check
        Health.MARGINAL -> Icons.Rounded.WarningAmber
        Health.POOR -> Icons.Rounded.Close
        Health.NO_LOCK -> Icons.Rounded.Remove
    }

@get:StringRes
val Health.label: Int
    get() = when (this) {
        Health.GOOD -> R.string.health_good
        Health.MARGINAL -> R.string.health_marginal
        Health.POOR -> R.string.health_poor
        Health.NO_LOCK -> R.string.health_no_lock
    }

/** One-word health with its icon and colour (SPEC `HealthChip`). */
@Composable
fun HealthChip(health: Health, modifier: Modifier = Modifier) {
    val color = health.color()
    Row(
        modifier = modifier
            .background(color.copy(alpha = 0.12f), MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(health.icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(stringResource(health.label), style = MaterialTheme.typography.labelMedium, color = color)
    }
}
