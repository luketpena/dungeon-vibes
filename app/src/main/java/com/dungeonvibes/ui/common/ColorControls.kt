package com.dungeonvibes.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.dungeonvibes.R
import com.dungeonvibes.ui.scenes.SceneSwatch

/**
 * A slider whose track is a gradient (hue, saturation, brightness, temperature). Moves only on a
 * deliberate sideways drag, like every slider in the app (see SteadySlider). A 0–1 range is a
 * percentage (saturation, brightness) and moves in [PERCENT_STEP]s; hue and temperature stay smooth.
 */
@Composable
fun GradientSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    brush: Brush,
    modifier: Modifier = Modifier,
) {
    val percent = valueRange.start == 0f && valueRange.endInclusive == 1f
    val change: (Float) -> Unit = if (percent) {
        { onValueChange(snapPercent(it)) }
    } else {
        onValueChange
    }
    SteadySlider(value, change, modifier, valueRange, trackBrush = brush)
}

/** Percent sliders move in 5 % steps (owner rule: no more precision than is useful). */
const val PERCENT_STEP = 0.05f

/** [value] (0–1) to the nearest [PERCENT_STEP]. */
fun snapPercent(value: Float): Float = ((value / PERCENT_STEP).roundToInt() * PERCENT_STEP).coerceIn(0f, 1f)

/**
 * A duration to the step the owner wants (Oct 23, 2026): tenths of a second under 1 s, half
 * seconds from 1 s up. Every time slider in the app goes through this.
 */
fun snapMs(raw: Float): Int {
    val step = if (raw < 1_000f) 100 else 500
    return (raw / step).roundToInt() * step
}

/**
 * A scene's palette: one swatch per lit group, or "Empty scene" when nothing is lit. Groups in
 * [failedSlotIds] get a small golden "!" (their bulbs didn't respond). A group with effects has a
 * white ✦ inside its swatch (see [Swatch]).
 */
@Composable
fun ScenePalette(
    swatches: List<SceneSwatch>,
    modifier: Modifier = Modifier,
    size: SwatchSize = SwatchSize.Small,
    failedSlotIds: Set<Long> = emptySet(),
) {
    if (swatches.isEmpty()) {
        Text(
            stringResource(R.string.scene_empty_palette),
            style = MaterialTheme.typography.bodySmall,
            // A softer version of the surrounding text color, readable on any card.
            color = LocalContentColor.current.copy(alpha = 0.7f),
            modifier = modifier,
        )
        return
    }
    val diameter = size.diameter
    Row(
        horizontalArrangement = Arrangement.spacedBy(if (failedSlotIds.isEmpty()) 4.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        val count = swatches.size
        for (i in 0 until count) {
            val swatch = swatches[i]
            if (swatch.slotId in failedSlotIds) {
                Box {
                    Swatch(swatch.color, size, shiftColors = swatch.shiftColors, hasEffects = swatch.hasEffects)
                    Icon(
                        painterResource(R.drawable.ic_warning),
                        contentDescription = stringResource(R.string.play_slot_failed),
                        tint = warningGold(),
                        modifier = Modifier
                            .size(diameter * 0.7f)
                            .align(Alignment.TopEnd)
                            .offset(x = diameter * 0.3f, y = -diameter * 0.3f),
                    )
                }
            } else {
                Swatch(swatch.color, size, shiftColors = swatch.shiftColors, hasEffects = swatch.hasEffects)
            }
        }
    }
}
