package com.dungeonvibes.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.dungeonvibes.R
import com.dungeonvibes.ui.scenes.SceneSwatch

/**
 * A slider whose track is a gradient (hue, saturation, brightness, temperature). Moves only on a
 * deliberate sideways drag, like every slider in the app (see SteadySlider).
 */
@Composable
fun GradientSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    brush: Brush,
    modifier: Modifier = Modifier,
) {
    SteadySlider(value, onValueChange, modifier, valueRange, trackBrush = brush)
}

/**
 * One circle showing a slot's color in a scene; [color] null means the slot is off. With two or
 * more [shiftColors] (a Color shift palette) the circle shows them all as a color wheel.
 */
@Composable
fun Swatch(color: Color?, size: Dp, modifier: Modifier = Modifier, shiftColors: List<Color> = emptyList()) {
    val outline = MaterialTheme.colorScheme.outline
    if (color == null) {
        Box(modifier.size(size).border(1.5.dp, outline, CircleShape))
        return
    }
    if (shiftColors.size >= 2) {
        // Close the loop so the last color blends back into the first.
        val brush = remember(shiftColors) { Brush.sweepGradient(shiftColors + shiftColors[0]) }
        Box(modifier.size(size).background(brush, CircleShape).border(1.dp, outline.copy(alpha = 0.4f), CircleShape))
        return
    }
    Box(modifier.size(size).background(color, CircleShape).border(1.dp, outline.copy(alpha = 0.4f), CircleShape))
}

/**
 * A scene's palette: one swatch per lit group, or "Empty scene" when nothing is lit. Groups in
 * [failedSlotIds] get a small golden "!" (their bulbs didn't respond). A group with effects has a
 * white ✦ inside its swatch.
 */
@Composable
fun ScenePalette(
    swatches: List<SceneSwatch>,
    modifier: Modifier = Modifier,
    size: Dp = 14.dp,
    failedSlotIds: Set<Long> = emptySet(),
) {
    val effectsLabel = stringResource(R.string.fx_has_effects)
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
                    PaletteSwatch(swatch, size, effectsLabel)
                    Icon(
                        painterResource(R.drawable.ic_warning),
                        contentDescription = stringResource(R.string.play_slot_failed),
                        tint = warningGold(),
                        modifier = Modifier
                            .size(size * 0.7f)
                            .align(Alignment.TopEnd)
                            .offset(x = size * 0.3f, y = -size * 0.3f),
                    )
                }
            } else {
                PaletteSwatch(swatch, size, effectsLabel)
            }
        }
    }
}

/** A palette swatch, with a white ✦ centered in it when its group has effects. */
@Composable
private fun PaletteSwatch(swatch: SceneSwatch, size: Dp, effectsLabel: String) {
    if (!swatch.hasEffects) {
        Swatch(swatch.color, size, shiftColors = swatch.shiftColors)
        return
    }
    Box(contentAlignment = Alignment.Center, modifier = Modifier.semantics { contentDescription = effectsLabel }) {
        Swatch(swatch.color, size, shiftColors = swatch.shiftColors)
        EffectsStar(size)
    }
}

/**
 * The ✦ drawn as a shape rather than text: the character comes from a fallback symbol font whose
 * metrics put it below center, while a path sits on the circle's exact middle at every size. A thin
 * dark edge keeps it visible on white and pale swatches. The path is built once per size.
 */
@Composable
private fun EffectsStar(size: Dp) {
    Spacer(
        Modifier
            .size(size)
            .drawWithCache {
                val r = this.size.minDimension * STAR_RADIUS
                val c = r * STAR_WAIST
                // The cache scope has a size but no center (that's only in the draw block).
                val x = this.size.width / 2f
                val y = this.size.height / 2f
                // Four points joined by inward curves, like the ✦ glyph.
                val star = Path().apply {
                    moveTo(x, y - r)
                    quadraticTo(x + c, y - c, x + r, y)
                    quadraticTo(x + c, y + c, x, y + r)
                    quadraticTo(x - c, y + c, x - r, y)
                    quadraticTo(x - c, y - c, x, y - r)
                    close()
                }
                val edge = Stroke(width = STAR_EDGE.toPx(), join = StrokeJoin.Round)
                onDrawBehind {
                    drawPath(star, StarEdgeColor, style = edge)
                    drawPath(star, Color.White)
                }
            },
    )
}

/** Point-to-center distance as a share of the swatch's diameter (tips span 64 % of it). */
private const val STAR_RADIUS = 0.32f

/** How far the curves between points stay from the center, as a share of the point distance. */
private const val STAR_WAIST = 0.16f

private val STAR_EDGE = 1.5.dp
private val StarEdgeColor = Color.Black.copy(alpha = 0.45f)
