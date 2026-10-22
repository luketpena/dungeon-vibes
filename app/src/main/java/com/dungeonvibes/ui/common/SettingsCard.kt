package com.dungeonvibes.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A group of related settings in an outlined box: a header, an optional [hint] under it, then the
 * controls. With [enabled] set, the header carries an on/off switch and the controls show only
 * while it's on (their settings are kept while off); with [enabled] null there's no switch and
 * the controls always show.
 */
@Composable
fun SettingsCard(
    title: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    enabled: Boolean? = null,
    onEnabledChange: (Boolean) -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    OutlinedCard(modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (enabled != null) Modifier.clickable { onEnabledChange(!enabled) } else Modifier)
                // Same header height with or without a switch, so stacked cards line up.
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (hint != null) {
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (enabled != null) Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
        AnimatedVisibility(visible = enabled != false) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), content = content)
        }
    }
}
