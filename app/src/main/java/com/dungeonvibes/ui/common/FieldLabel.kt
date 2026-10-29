package com.dungeonvibes.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The one way a form control is labelled: what it is on the left (muted), its current value on
 * the right (bright), both in labelLarge. The value uses tabular digits, so it doesn't jiggle
 * sideways as a slider moves. Leave [value] out for controls that show their own (a choice, a
 * color slider).
 */
@Composable
fun FieldLabel(label: String, value: String? = null, topPadding: Dp = 8.dp) {
    val style = MaterialTheme.typography.labelLarge
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = topPadding),
    ) {
        Text(
            label,
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Text(
                value,
                style = style.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
