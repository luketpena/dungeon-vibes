package com.dungeonvibes.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dungeonvibes.R

/** The swatch sizes the app uses, so the same kind of place always gets the same circle. */
enum class SwatchSize(val diameter: Dp, internal val starEdge: Dp) {
    /** Palettes in lists (the scene library, pickers). */
    Small(14.dp, 1.5.dp),

    /** Palettes on scene tiles and the FX row in Play. */
    Medium(20.dp, 1.5.dp),

    /** FX library rows. */
    Large(24.dp, 1.5.dp),

    /** Editor rows and the Multi-color palette. */
    ExtraLarge(36.dp, 2.dp),
}

/**
 * One circle showing a slot's color; [color] null means the slot is off (an empty ring). With two
 * or more [shiftColors] (a Multi-color palette) it shows them all as a color wheel. [hasEffects]
 * puts a white ✦ in the middle (not on an off swatch).
 *
 * Everything is drawn by one node: fill, edge and star, with the gradient and star path built
 * once per size and inputs rather than every frame.
 */
@Composable
fun Swatch(
    color: Color?,
    size: SwatchSize,
    modifier: Modifier = Modifier,
    shiftColors: List<Color> = emptyList(),
    hasEffects: Boolean = false,
) {
    val outline = MaterialTheme.colorScheme.outline
    val showStar = hasEffects && color != null
    // Read outside the draw block, where `size` means the canvas size.
    val starEdgeWidth = size.starEdge
    val semantics = if (showStar) {
        val label = stringResource(R.string.fx_has_effects)
        Modifier.semantics { contentDescription = label }
    } else {
        Modifier
    }
    Box(
        modifier
            .then(semantics)
            .size(size.diameter)
            .drawWithCache {
                val radius = this.size.minDimension / 2f
                // The cache scope has a size but no center (that's only in the draw block).
                val mid = Offset(this.size.width / 2f, this.size.height / 2f)
                if (color == null) {
                    val ringWidth = OFF_RING.toPx()
                    val ring = Stroke(ringWidth)
                    return@drawWithCache onDrawBehind {
                        drawCircle(outline, radius - ringWidth / 2f, mid, style = ring)
                    }
                }
                // Close the loop so the last color blends back into the first.
                val wheel = if (shiftColors.size >= 2) Brush.sweepGradient(shiftColors + shiftColors[0], mid) else null
                val edgeWidth = EDGE.toPx()
                val edge = Stroke(edgeWidth)
                val edgeColor = outline.copy(alpha = EDGE_ALPHA)
                val star = if (showStar) starPath(mid, radius * 2f) else null
                val starEdge = if (showStar) Stroke(starEdgeWidth.toPx(), join = StrokeJoin.Round) else null
                onDrawBehind {
                    if (wheel != null) drawCircle(wheel, radius, mid) else drawCircle(color, radius, mid)
                    drawCircle(edgeColor, radius - edgeWidth / 2f, mid, style = edge)
                    if (star != null) {
                        // A thin dark edge keeps the star visible on white and pale swatches.
                        drawPath(star, StarEdgeColor, style = starEdge!!)
                        drawPath(star, Color.White)
                    }
                }
            },
    )
}

/**
 * The ✦ as a path rather than text: the character comes from a fallback symbol font whose metrics
 * put it below center, while a path sits on the circle's exact middle at every size.
 */
private fun starPath(mid: Offset, diameter: Float): Path {
    val r = diameter * STAR_RADIUS
    val c = r * STAR_WAIST
    val x = mid.x
    val y = mid.y
    // Four points joined by inward curves, like the ✦ glyph.
    return Path().apply {
        moveTo(x, y - r)
        quadraticTo(x + c, y - c, x + r, y)
        quadraticTo(x + c, y + c, x, y + r)
        quadraticTo(x - c, y + c, x - r, y)
        quadraticTo(x - c, y - c, x, y - r)
        close()
    }
}

/** Point-to-center distance as a share of the swatch's diameter (tips span 64 % of it). */
private const val STAR_RADIUS = 0.32f

/** How far the curves between points stay from the center, as a share of the point distance. */
private const val STAR_WAIST = 0.16f

private val OFF_RING = 1.5.dp
private val EDGE = 1.dp
private const val EDGE_ALPHA = 0.4f
private val StarEdgeColor = Color.Black.copy(alpha = 0.45f)
