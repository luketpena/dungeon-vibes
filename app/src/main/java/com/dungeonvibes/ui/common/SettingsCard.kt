package com.dungeonvibes.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/**
 * A group of related settings in a solid box, a step lighter than the page: a header (an [icon] that sets the box's mood,
 * then the title in the header font, both in the gold accent), an optional [hint] under it, then the controls. With
 * [enabled] set, the header carries an on/off switch and the controls show only while it's on
 * (their settings are kept while off); with [enabled] null there's no switch and the controls
 * always show. An [action] (say, an add button) sits at the right end of the header.
 */
@Composable
fun SettingsCard(
    title: String,
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    hint: String? = null,
    enabled: Boolean? = null,
    onEnabledChange: (Boolean) -> Unit = {},
    action: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    // Solid rather than outlined (owner experiment): the fill separates a group from the page
    // and from the next box more clearly than a hairline. A soft shadow lifts it, and a barely
    // lighter edge catches the light along its rim.
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = CARD_SHADOW),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = RIM_ALPHA)),
    ) {
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
                // Icon and title share a row so the icon stays level with the title, top left,
                // whether or not a hint follows.
                // Icon and title share the gold accent, so each group's header reads as one mark.
                val accent = MaterialTheme.colorScheme.primary
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(icon),
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(ICON_SIZE),
                    )
                    Spacer(Modifier.width(ICON_GAP))
                    Text(title, style = MaterialTheme.typography.titleLarge, color = accent)
                }
                if (hint != null) {
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        // Lined up under the title, not the icon.
                        modifier = Modifier.padding(start = ICON_SIZE + ICON_GAP),
                    )
                }
            }
            action()
            if (enabled != null) Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
        AnimatedVisibility(visible = enabled != false) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), content = content)
        }
    }
}

/** The space between stacked settings boxes, everywhere they stack (owner preference: roomy). */
val SettingsCardGap = 20.dp

private val ICON_SIZE = 24.dp

/** How far the box seems to float: just enough for a soft shadow. */
private val CARD_SHADOW = 3.dp

/** The rim: the text color at a whisper, a hair lighter than the box. */
private const val RIM_ALPHA = 0.07f
private val ICON_GAP = 10.dp
