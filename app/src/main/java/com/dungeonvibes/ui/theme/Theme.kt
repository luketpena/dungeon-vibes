package com.dungeonvibes.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/** The app's look: the indigo and gold palette (Color.kt) and the two fonts (Type.kt). */
@Composable
fun DungeonVibesTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AppColors, typography = AppTypography, content = content)
}
