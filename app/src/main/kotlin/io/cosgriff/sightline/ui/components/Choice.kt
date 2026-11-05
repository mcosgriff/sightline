package io.cosgriff.sightline.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import io.cosgriff.sightline.ui.theme.sightlineColors

/** A row of [Choice]s that wraps. */
@Composable
fun Choices(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

/** One option of a few, as a chip; the selected one in the accent colour and marked selected for TalkBack. */
@Composable
fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.sightlineColors
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        shape = MaterialTheme.shapes.small,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = colors.surface,
            labelColor = colors.textPrimary,
            selectedContainerColor = colors.accent.copy(alpha = 0.18f),
            selectedLabelColor = colors.accent,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = colors.outline,
            selectedBorderColor = colors.accent,
        ),
    )
}
