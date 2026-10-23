package com.dungeonvibes.ui.adventures.play

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import kotlinx.coroutines.launch

/*
 * A tile's play state: an outlined play triangle while idle, three spinning arcs while its scene
 * plays. Both shapes are three strokes, and every stroke is one cubic curve (a straight side is a
 * curve with its handles on the line), so the triangle's sides and the spinner's arcs line up one
 * to one: side A→B becomes the arc over the top right, B→C the bottom right, C→A the left. Morphing
 * between them later is just blending each curve's four points.
 */

/** One stroke: a cubic curve from (x0, y0) to (x3, y3) with handles (x1, y1) and (x2, y2), on a 24 × 24 grid. */
private class CurveStroke(
    val x0: Float, val y0: Float,
    val x1: Float, val y1: Float,
    val x2: Float, val y2: Float,
    val x3: Float, val y3: Float,
) {
    /**
     * Rewrites [out] as this curve blended toward [to] by [t] (0 this, 1 [to]), pushed out from the
     * grid's center by [grow] (1 = as drawn), scaled to pixels.
     */
    fun blendInto(to: CurveStroke, t: Float, scale: Float, out: Path, grow: Float = 1f) {
        val k = scale * grow
        val c = CENTER * scale * (1f - grow)
        out.reset()
        out.moveTo(mix(x0, to.x0, t) * k + c, mix(y0, to.y0, t) * k + c)
        out.cubicTo(
            mix(x1, to.x1, t) * k + c, mix(y1, to.y1, t) * k + c,
            mix(x2, to.x2, t) * k + c, mix(y2, to.y2, t) * k + c,
            mix(x3, to.x3, t) * k + c, mix(y3, to.y3, t) * k + c,
        )
    }
}

private fun mix(a: Float, b: Float, t: Float): Float = a + (b - a) * t

private const val CENTER = 12f

/** A straight stroke, as a curve with its handles a third and two thirds of the way along. */
private fun line(ax: Float, ay: Float, bx: Float, by: Float): CurveStroke {
    val dx = (bx - ax) / 3f
    val dy = (by - ay) / 3f
    return CurveStroke(ax, ay, ax + dx, ay + dy, bx - dx, by - dy, bx, by)
}

/**
 * An arc of the circle at ([cx], [cy]) with radius [r], from [startDeg] clockwise through
 * [sweepDeg] (0° points right, as on screen). One cubic is accurate to well under a pixel for
 * sweeps this short.
 */
private fun arc(cx: Float, cy: Float, r: Float, startDeg: Float, sweepDeg: Float): CurveStroke {
    val a = startDeg * DEG
    val b = (startDeg + sweepDeg) * DEG
    // Handle length for a circular arc: 4/3 · tan(sweep / 4) of the radius.
    val k = (4f / 3f) * tan((b - a) / 4f) * r
    val ax = cx + r * cos(a)
    val ay = cy + r * sin(a)
    val bx = cx + r * cos(b)
    val by = cy + r * sin(b)
    return CurveStroke(
        ax, ay,
        ax - k * sin(a), ay + k * cos(a),
        bx + k * sin(b), by - k * cos(b),
        bx, by,
    )
}

/** Degrees to radians. */
private const val DEG = 0.017453292f

/*
 * The play triangle is equilateral around the grid's center (corners 7 units out at 0°, 120° and
 * 240°): centered by its centroid, so it looks centered, and the same shape after a third of a turn,
 * so the spinner can stop at any third and close back into it exactly.
 */
private const val TIP_X = 19f
private const val BACK_X = 8.5f
private const val TOP_Y = 5.9378f
private const val BOTTOM_Y = 18.0622f

private val PlayStrokes = arrayOf(
    line(BACK_X, TOP_Y, TIP_X, 12f),
    line(TIP_X, 12f, BACK_X, BOTTOM_Y),
    line(BACK_X, BOTTOM_Y, BACK_X, TOP_Y),
)

/**
 * Three 80° arcs with 40° gaps, each centered on the triangle side it morphs from and running the
 * same way round, so no stroke flips or crosses on the way.
 */
private val SpinnerStrokes = Array(3) { arc(12f, 12f, SPINNER_RADIUS, -100f + 120f * it, ARC_SWEEP) }

/**
 * The circle through the triangle's corners, as three 120° arcs, one per side: each runs corner to
 * corner the same way round as its side, so the sides just bow out into it.
 */
private val CircleStrokes = Array(3) { arc(CENTER, CENTER, CORNER_RADIUS, -120f + 120f * it, 120f) }

/** How far the triangle's corners are from the center (and the radius of the circle through them). */
private const val CORNER_RADIUS = 7f

private const val SPINNER_RADIUS = 8f
private const val ARC_SWEEP = 80f
private const val GRID = 24f
private const val SPIN_MS = 1_400

/** The spin's steady speed, in degrees per millisecond. */
private const val SPIN_SPEED = 360f / SPIN_MS

/** How long the triangle takes to open into the spinner. */
private const val MORPH_MS = 450

/** On stop, the spinner coasts at least this far, so it never stops abruptly. */
private const val MIN_COAST_DEG = 40f

/** Speeds up evenly from rest: ends at twice its average speed, so the distance can be chosen to land on the spin's speed. */
private val SpeedUp = Easing { it * it }

/** The mirror image: starts at twice its average speed and slows evenly to rest. */
private val SlowDown = Easing { 1f - (1f - it) * (1f - it) }

private val ICON_SIZE = 24.dp
private val STROKE_WIDTH = 2.dp

/**
 * The play / playing mark in a tile's corner. Idle: an outlined play triangle in the card's text
 * color. [running]: three arcs turning steadily, each slowly cycling through the scene's [colors]
 * (the text color if none), a third of the cycle apart, so they never show the same color at once
 * when the scene has more than one.
 *
 * Between the two it morphs: each side of the triangle bends into its arc while the colors blend
 * in and the spin speeds up to its steady pace. On stop the spinner coasts, slowing evenly, to the
 * next third of a turn (where the arcs line up with the triangle again) while the arcs straighten
 * back into it. Tiles that appear already playing (a tab switch) start spinning without the morph.
 * With system animations removed it just switches. Decorative: the card tells the screen reader.
 */
@Composable
fun PlayStateIcon(running: Boolean, colors: List<Color>, modifier: Modifier = Modifier) {
    // Opaque: the softening is applied to the whole mark at once (below), so the corners where
    // strokes overlap don't come out brighter than the rest.
    val content = LocalContentColor.current
    val idle = content.copy(alpha = 1f)
    // A dimmed content color (a disabled button) dims the whole layer instead of each stroke.
    val baseAlpha = content.alpha
    val animate = rememberAnimationsEnabled()
    val palette by rememberUpdatedState(colors)
    // Position in the color cycle, in palette steps (0 until the palette's size); read at draw time.
    val cycle = remember { Animatable(0f) }
    val colorCount = colors.size
    LaunchedEffect(running, animate, colorCount) {
        if (!running || !animate || colorCount < 2) return@LaunchedEffect
        val span = colorCount.toFloat()
        while (true) {
            cycle.snapTo(cycle.value % span)
            val from = cycle.value
            cycle.animateTo(from + span, tween((COLOR_STEP_MS * span).toInt(), easing = LinearEasing))
        }
    }
    // 0 = triangle, 1 = spinner; and the spinner's turn, in degrees.
    val morph = remember { Animatable(if (running) 1f else 0f) }
    val angle = remember { Animatable(0f) }
    LaunchedEffect(running, animate) {
        if (!animate) {
            morph.snapTo(if (running) 1f else 0f)
            angle.snapTo(0f)
            return@LaunchedEffect
        }
        if (running) {
            if (morph.value < 1f) {
                // Open up while speeding up from rest; covering half the steady speed's distance
                // over the morph means it ends exactly at the steady speed, with no hitch.
                launch { morph.animateTo(1f, tween(MORPH_MS, easing = FastOutSlowInEasing)) }
                angle.animateTo(angle.value + SPIN_SPEED * MORPH_MS / 2f, tween(MORPH_MS, easing = SpeedUp))
            }
            while (true) {
                angle.snapTo(angle.value % 360f)
                angle.animateTo(angle.value + 360f, tween(SPIN_MS, easing = LinearEasing))
            }
        }
        if (morph.value <= 0f) return@LaunchedEffect
        // Coast to the next third of a turn, slowing evenly from the steady speed: a coast of d
        // degrees takes 2d / speed. The arcs close into the triangle over the same time.
        var coast = ceil(angle.value / 120f) * 120f - angle.value
        if (coast < MIN_COAST_DEG) coast += 120f
        val ms = (2f * coast / SPIN_SPEED).toInt()
        launch { morph.animateTo(0f, tween(ms, easing = FastOutSlowInEasing)) }
        angle.animateTo(angle.value + coast, tween(ms, easing = SlowDown))
        // A third of a turn leaves the triangle looking the same, so this reset can't be seen.
        angle.snapTo(0f)
    }
    Spacer(
        modifier
            .size(ICON_SIZE)
            // One offscreen layer: strokes draw opaque, then the layer fades as a whole, softer
            // while idle and fully opaque as the scene's colors come in.
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
                alpha = baseAlpha * (IDLE_ALPHA + (1f - IDLE_ALPHA) * morph.value)
            }
            .drawWithCache {
                val scale = size.minDimension / GRID
                val stroke = Stroke(width = STROKE_WIDTH.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                // Reused every frame; rebuilt only while the shape is changing.
                val paths = Array(3) { Path() }
                var builtAt = -1f
                onDrawBehind {
                    // Read at draw time: morphing and spinning never recompose the tile.
                    val t = morph.value
                    if (t != builtAt) {
                        for (i in 0 until 3) PlayStrokes[i].blendInto(SpinnerStrokes[i], t, scale, paths[i])
                        builtAt = t
                    }
                    val colorsNow = palette
                    val count = colorsNow.size
                    val shift = cycle.value
                    rotate(angle.value) {
                        for (i in 0 until 3) {
                            // Minus: colors pass from arc to arc against the spin (owner request).
                            val arc = if (count == 0) idle else paletteAt(colorsNow, i * count / 3f - shift)
                            drawPath(paths[i], lerp(idle, arc, t), style = stroke)
                        }
                    }
                }
            },
    )
}

/** The idle triangle is a little softer than the scene name, so it doesn't compete with it. */
private const val IDLE_ALPHA = 0.8f

/** How long each arc takes to move from one palette color to the next. */
private const val COLOR_STEP_MS = 2_500f

/**
 * The palette color at [position] (in palette steps, wrapping): it lingers on each color and
 * blends to the next with an ease (smoothstep), so the arcs read as colors rather than a mush.
 */
private fun paletteAt(colors: List<Color>, position: Float): Color {
    val count = colors.size
    if (count == 1) return colors[0]
    var p = position % count
    if (p < 0f) p += count
    val index = p.toInt()
    val f = p - index
    val eased = f * f * (3f - 2f * f)
    return lerp(colors[index % count], colors[(index + 1) % count], eased)
}

/* The FX burst */

/**
 * An FX's play button: the same outlined triangle, which bursts each time [fires] goes up. It's one
 * shape the whole way (the triangle's own three strokes, nothing drawn over them) in one continuous
 * motion: the sides bow out into the circle through the corners as it swells outward, thinning a
 * little at its widest like a pulse, then it pulls back and straightens into the triangle, in the
 * FX's [color] (the button's color when the FX has none). A tap during a burst queues one more.
 * With system animations removed it stays a triangle. Decorative: the button carries the label.
 */
@Composable
fun FxBurstIcon(fires: Int, color: Color?, modifier: Modifier = Modifier) {
    val content = LocalContentColor.current
    val idle = content.copy(alpha = 1f)
    val baseAlpha = content.alpha
    val burstColor = color ?: idle
    val animate = rememberAnimationsEnabled()
    // One timeline, 0 → 1, for the whole burst; everything else is a smooth curve of it.
    val progress = remember { Animatable(1f) }
    val currentFires = rememberUpdatedState(fires)
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        var seen = currentFires.value
        // Collected one at a time: a tap during a burst plays once this one is done (taps made
        // meanwhile fold into one), so a burst is never cut off mid-motion.
        snapshotFlow { currentFires.value }.collect { count ->
            if (count == seen) return@collect
            seen = count
            progress.snapTo(0f)
            progress.animateTo(1f, tween(BURST_MS, easing = LinearEasing))
        }
    }
    Spacer(
        modifier
            .size(ICON_SIZE)
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
                alpha = baseAlpha * (IDLE_ALPHA + (1f - IDLE_ALPHA) * shapeAt(progress.value))
            }
            .drawWithCache {
                val scale = size.minDimension / GRID
                val width = STROKE_WIDTH.toPx()
                var stroke = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)
                val paths = Array(3) { Path() }
                var builtShape = -1f
                var builtSwell = -1f
                onDrawBehind {
                    val p = progress.value
                    val t = shapeAt(p)
                    val grow = swellAt(p)
                    if (t != builtShape || grow != builtSwell) {
                        for (i in 0 until 3) PlayStrokes[i].blendInto(CircleStrokes[i], t, scale, paths[i], grow)
                        // The line thins as the circle swells (the pulse), back to full as it settles.
                        val swollen = (grow - 1f) / (SWELL - 1f)
                        stroke = Stroke(width = width * (1f - PULSE_THIN * swollen), cap = StrokeCap.Round, join = StrokeJoin.Round)
                        builtShape = t
                        builtSwell = grow
                    }
                    val ink = lerp(idle, burstColor, t)
                    for (i in 0 until 3) drawPath(paths[i], ink, style = stroke)
                }
            },
    )
}

/*
 * The burst's curves, both of one timeline p (0 → 1). Each starts and ends with zero speed, and
 * they overlap, so the motion never stops between stages:
 *   shape (triangle 0 → circle 1): rounds out over the first 40 %, straightens over the last 45 %;
 *   swell (size): grows from 10 % and is back by 85 %, peaking a little before the middle.
 */

private fun shapeAt(p: Float): Float = smoothstep(0f, 0.4f, p) * (1f - smoothstep(0.55f, 1f, p))

private fun swellAt(p: Float): Float {
    val phase = ((p - 0.1f) / 0.75f).coerceIn(0f, 1f)
    val s = sin(PI_F * phase)
    return 1f + (SWELL - 1f) * s * s
}

/** 0 below [edge0], 1 above [edge1], an S-curve with flat ends between. */
private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private const val PI_F = 3.1415927f

/** The whole burst, triangle to triangle. */
private const val BURST_MS = 550

/** How big the circle swells, as a multiple of its size (it stays inside the 24 dp icon). */
private const val SWELL = 1.45f

/** How much thinner the line gets at the height of the swell (a share of its width). */
private const val PULSE_THIN = 0.3f
