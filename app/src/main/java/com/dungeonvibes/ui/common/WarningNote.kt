package com.dungeonvibes.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.dungeonvibes.R

private val GoldOnDark = Color(0xFFFFC94D)
private val GoldOnLight = Color(0xFF9A6700)

/**
 * A small golden-yellow "heads up" line with a warning icon, for side effects that are easy to
 * miss (like a bulb moving out of another slot). Darker gold on light backgrounds stays readable.
 */
@Composable
fun WarningNote(text: String, modifier: Modifier = Modifier) {
    val gold = warningGold()
    // Top-aligned, so a note that wraps onto two lines keeps its icon beside the first line.
    Row(verticalAlignment = Alignment.Top, modifier = modifier) {
        Icon(
            painterResource(R.drawable.ic_warning),
            contentDescription = null,
            tint = gold,
            modifier = Modifier.padding(top = 1.dp).size(16.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = gold)
    }
}

/** Golden yellow for warnings; darker on light backgrounds so it stays readable. */
@Composable
fun warningGold(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) GoldOnDark else GoldOnLight
