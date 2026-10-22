package com.dungeonvibes.ui.adventures.play

import android.provider.Settings
import androidx.compose.animation.Animatable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dungeonvibes.light.CommandedLight
import com.dungeonvibes.light.LightState
import com.dungeonvibes.ui.common.LightColors
import com.dungeonvibes.ui.common.PressHaptic
import com.dungeonvibes.ui.scenes.SceneSwatch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/*
 * The active tile's glow: a colored light around the card that follows what the bulbs are being
 * sent, like a TV backlight. Each bulb owns a stretch of the edge, in group order and then name
 * order, and the stretches blend where they meet; a group's brightness sets how strongly its
 * stretch shines. Colors come from LightMirror (our own commands), so the card moves with the room
 * using the same fades the bulbs get.
 */

/** One stretch of the glow: the bulb it follows (null: its group has no bulbs) and its swatch color. */
@Immutable
data class GlowSource(val bulbKey: String?, val fallback: Color)

/**
 * The glow's stretches for a tile: one per bulb of each lit group, in the tile's swatch order; a lit
 * group without bulbs shows its swatch color. With All lights on, the one swatch covers every bulb.
 */
internal fun glowSources(swatches: List<SceneSwatch>, allLightsOn: Boolean, bulbs: GlowBulbs): List<GlowSource> {
    val count = swatches.size
    if (count == 0) return emptyList()
    val result = ArrayList<GlowSource>(if (allLightsOn) bulbs.all.size.coerceAtLeast(1) else count)
    for (i in 0 until count) {
        val swatch = swatches[i]
        val keys = if (allLightsOn) bulbs.all else bulbs.bySlot[swatch.slotId]
        if (keys.isNullOrEmpty()) {
            result.add(GlowSource(null, swatch.color))
            continue
        }
        val keyCount = keys.size
        for (k in 0 until keyCount) result.add(GlowSource(keys[k], swatch.color))
    }
    return result
}

/** The glow color for a commanded light: its hue or white at full value (brightness is the level). */
internal fun glowColorOf(state: LightState): Color {
    val kelvin = state.kelvin
    if (kelvin != null) return LightColors.kelvinToColor(kelvin)
    return Color.hsv(state.hue.coerceIn(0f, 360f), state.saturation.coerceIn(0f, 1f), 1f)
}

/** How strongly a bulb at [brightness] shines in the glow: nothing when dark, then 35–100 % like swatches. */
internal fun glowLevelOf(brightness: Float): Float =
    if (brightness <= DARK_BRIGHTNESS) 0f else 0.35f + 0.65f * brightness.coerceAtMost(1f)

/** One stretch's animated color and level (0–1); read only while drawing, so animating never recomposes. */
@Stable
class GlowSegment internal constructor(fallback: Color) {
    internal val color = Animatable(fallback)
    internal val level = Animatable(0f)

    /** Moves toward what was last sent, over its fade, the way the bulb does. */
    internal suspend fun follow(sent: CommandedLight?, fallback: Color): Unit = coroutineScope {
        if (sent == null) {
            // Nothing sent to this bulb yet: rest on the scene's swatch color.
            launch { color.animateTo(fallback, tween(GLOW_IN_MS)) }
            level.animateTo(STATIC_LEVEL, tween(GLOW_IN_MS))
            return@coroutineScope
        }
        // Instant changes (flash edges) get a short ramp, so the screen never strobes.
        val ms = sent.fadeMs.coerceAtLeast(MIN_GLOW_FADE_MS)
        val target = sent.color
        if (target == null) {
            // Turned off: the light fades out; its color stays where it was.
            level.animateTo(0f, tween(ms, easing = LinearEasing))
            return@coroutineScope
        }
        var targetLevel = glowLevelOf(target.brightness)
        // Fast rises (flashes, strobes) flare only part of the way.
        if (sent.fadeMs < FLASH_FADE_MS) targetLevel = targetLevel.coerceAtMost(level.value + FLASH_MAX_RISE)
        launch { color.animateTo(glowColorOf(target), tween(ms, easing = LinearEasing)) }
        level.animateTo(targetLevel, tween(ms, easing = LinearEasing))
    }
}

@Stable
class SceneGlowState internal constructor(internal val segments: Array<GlowSegment>)

/**
 * Animates one segment per source. [live] false (system animations removed) holds every stretch
 * steady in its swatch color instead of following the bulbs.
 */
@Composable
private fun rememberSceneGlow(
    sources: List<GlowSource>,
    live: Boolean,
    observe: (String) -> StateFlow<CommandedLight?>,
): SceneGlowState {
    val count = sources.size
    val state = remember(sources) { SceneGlowState(Array(count) { GlowSegment(sources[it].fallback) }) }
    val currentObserve by rememberUpdatedState(observe)
    LaunchedEffect(state, live) {
        for (i in 0 until count) {
            val segment = state.segments[i]
            val source = sources[i]
            val key = source.bulbKey
            if (!live || key == null) {
                launch {
                    segment.color.snapTo(source.fallback)
                    if (live) segment.level.animateTo(STATIC_LEVEL, tween(GLOW_IN_MS)) else segment.level.snapTo(STATIC_LEVEL)
                }
                continue
            }
            // collectLatest: a new command cancels the fade in progress, which carries on from where it is.
            launch { currentObserve(key).collectLatest { segment.follow(it, source.fallback) } }
        }
    }
    return state
}

/** A slow 4 s breath (0–1) for scenes whose lights hold still, so the active card never looks dead. */
@Composable
private fun rememberBreath(): State<Float> =
    rememberInfiniteTransition(label = "glowBreath").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(BREATH_MS / 2, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath",
    )

/** False when the system's "Remove animations" (animator duration scale 0) is on. */
@Composable
fun rememberAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
}

/**
 * The glow for a tile, or an empty modifier when it isn't [active] or lights nothing: a blurred
 * light behind the card and a matching outline on top of it ([dashed] for the ghost tile). Scenes
 * without effects breathe gently on top of the mirrored colors; scenes with effects show the true
 * signal. With animations removed, the glow holds still in the scene's swatch colors.
 */
@Composable
fun rememberSceneGlowModifier(
    active: Boolean,
    swatches: List<SceneSwatch>,
    allLightsOn: Boolean,
    hasEffects: Boolean,
    bulbs: GlowBulbs,
    shape: Shape,
    observe: (String) -> StateFlow<CommandedLight?>,
    dashed: Boolean = false,
): Modifier {
    if (!active) return Modifier
    val animate = rememberAnimationsEnabled()
    val sources = remember(swatches, allLightsOn, bulbs) { glowSources(swatches, allLightsOn, bulbs) }
    if (sources.isEmpty()) return Modifier
    val glow = rememberSceneGlow(sources, live = animate, observe)
    val breath = if (animate && !hasEffects) rememberBreath() else null
    return Modifier.sceneGlow(glow, shape, breath).glowOutline(glow, shape, breath, dashed)
}

/**
 * The glow for something that plays the whole room (an adventure card): it follows every bulb in
 * [bulbs] (all assigned bulbs), so it shows exactly what the room shows, and dark bulbs add nothing.
 * Breathes while no effects run, like a tile.
 */
@Composable
fun rememberRoomGlowModifier(
    active: Boolean,
    bulbs: List<String>,
    effectsRunning: Boolean,
    shape: Shape,
    observe: (String) -> StateFlow<CommandedLight?>,
): Modifier {
    if (!active || bulbs.isEmpty()) return Modifier
    val animate = rememberAnimationsEnabled()
    val sources = remember(bulbs) { bulbs.map { GlowSource(it, Color.Transparent) } }
    val glow = rememberSceneGlow(sources, live = true, observe)
    val breath = if (animate && !effectsRunning) rememberBreath() else null
    return Modifier.sceneGlow(glow, shape, breath).glowOutline(glow, shape, breath, dashed = false)
}

/* The card */

/** The shape every glowing card uses (Material's medium card corner), so its glow can match it. */
val GlowCardShape: Shape = RoundedCornerShape(12.dp)

/**
 * The app's dark card: scene tiles and adventures. Pass [glow] (from [rememberSceneGlowModifier] or
 * [rememberRoomGlowModifier], built with [GlowCardShape]) to light it up. With [squish] on it shrinks
 * while held, bounces back on release and ticks as the finger lands, instead of rippling.
 * [stateLabel] is what TalkBack reads as its state (e.g. "Active").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlowCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glow: Modifier = Modifier,
    squish: Boolean = true,
    stateLabel: String? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    elevation: Dp = 1.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interactionSource, enabled = squish) { haptics.performHapticFeedback(PressHaptic) }
    CompositionLocalProvider(LocalRippleConfiguration provides if (squish) null else LocalRippleConfiguration.current) {
        Card(
            onClick = onClick,
            modifier = modifier
                .pressScale(scale)
                .then(glow)
                .semantics { if (stateLabel != null) stateDescription = stateLabel },
            shape = GlowCardShape,
            colors = CardDefaults.cardColors(
                containerColor = containerColor,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = elevation),
            interactionSource = interactionSource,
            content = content,
        )
    }
}

/** Draws [glow] as a blurred light behind [shape]; every animated value is read here, at draw time. */
private fun Modifier.sceneGlow(glow: SceneGlowState, shape: Shape, breath: State<Float>?): Modifier =
    dropShadow(shape) {
        val pulse = breath?.value ?: 0f
        radius = (GLOW_RADIUS + GLOW_RADIUS_BREATH * pulse).toPx()
        spread = GLOW_SPREAD.toPx()
        alpha = 1f
        val segments = glow.segments
        val alphaScale = GLOW_MAX_ALPHA * (1f - BREATH_DEPTH * pulse)
        if (segments.size == 1) {
            brush = null
            color = segments[0].glowColor(alphaScale)
            return@dropShadow
        }
        brush = sweepOf(segments, alphaScale)
    }

/**
 * The same colors as an outline drawn over the card's edge (inside it, so nothing is clipped),
 * stronger than the glow and breathing only half as much, so it stays crisp.
 */
private fun Modifier.glowOutline(glow: SceneGlowState, shape: Shape, breath: State<Float>?, dashed: Boolean): Modifier =
    drawWithCache {
        val width = OUTLINE_WIDTH.toPx()
        val half = width / 2f
        // Inset by half the stroke so the whole line sits on the card; built once per size.
        val outline = shape.createOutline(size.copy(width = size.width - width, height = size.height - width), layoutDirection, this)
        val stroke = Stroke(width = width, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(14f, 10f)) else null)
        onDrawWithContent {
            drawContent()
            val segments = glow.segments
            val alphaScale = OUTLINE_MAX_ALPHA * (1f - BREATH_DEPTH * 0.5f * (breath?.value ?: 0f))
            val brush = if (segments.size == 1) SolidColor(segments[0].glowColor(alphaScale)) else sweepOf(segments, alphaScale)
            translate(half, half) { drawOutline(outline, brush, style = stroke) }
        }
    }

/** The segment's color at its level, scaled by [alphaScale]. */
private fun GlowSegment.glowColor(alphaScale: Float): Color =
    color.value.copy(alpha = (level.value * alphaScale).coerceIn(0f, 1f))

/**
 * Each segment gets an equal arc of a sweep around the center (clockwise from 3 o'clock), solid in
 * its middle and blending into its neighbors at the ends; the seam at 0/1 blends the last segment
 * into the first. The glow and the outline both use it, so they always match.
 */
private fun sweepOf(segments: Array<GlowSegment>, alphaScale: Float): Brush {
    val count = segments.size
    val stops = arrayOfNulls<Pair<Float, Color>>(count * 2 + 2)
    val first = segments[0].glowColor(alphaScale)
    val last = segments[count - 1].glowColor(alphaScale)
    val seam = lerp(last, first, 0.5f)
    val step = 1f / count
    val edge = step * BLEND_SHARE
    stops[0] = 0f to seam
    var s = 1
    for (i in 0 until count) {
        val c = if (i == 0) first else if (i == count - 1) last else segments[i].glowColor(alphaScale)
        val start = i * step
        stops[s++] = (start + edge) to c
        stops[s++] = (start + step - edge) to c
    }
    stops[s] = 1f to seam
    @Suppress("UNCHECKED_CAST")
    return Brush.sweepGradient(*(stops as Array<Pair<Float, Color>>))
}

/* The room edge: the same light along the nav, on every screen */

/**
 * A strip of light along the edge where the screen meets the navigation ([alongBottom]: the top
 * of the bottom bar; otherwise the inner edge of the side rail), following every assigned bulb in
 * group order, then name order, so what the room is doing shows wherever you are in the app. A
 * crisp line sits on the edge and a soft wash rises from it over the content, about 2.5× as deep
 * as a card's glow. Dark bulbs add nothing, so with everything off (or before anything has been
 * sent) the edge is plain. It breathes like a card while no effects run. Draws only; touches pass
 * through to the content underneath.
 */
@Composable
fun RoomGlowEdge(
    bulbs: List<String>,
    effectsRunning: Boolean,
    alongBottom: Boolean,
    observe: (String) -> StateFlow<CommandedLight?>,
    modifier: Modifier = Modifier,
) {
    if (bulbs.isEmpty()) return
    val animate = rememberAnimationsEnabled()
    // No swatch to fall back on here: a bulb nothing has been sent to stays dark.
    val sources = remember(bulbs) { bulbs.map { GlowSource(it, Color.Transparent) } }
    val glow = rememberSceneGlow(sources, live = true, observe)
    val breath = if (animate && !effectsRunning) rememberBreath() else null
    val extent = if (alongBottom) Modifier.fillMaxWidth().height(EDGE_DEPTH) else Modifier.fillMaxHeight().width(EDGE_DEPTH)
    Box(
        modifier
            .then(extent)
            // Offscreen, so the fade mask only cuts this strip, not the screen under it.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                val line = OUTLINE_WIDTH.toPx()
                val mask = if (alongBottom) Brush.verticalGradient(*EDGE_FADE) else Brush.horizontalGradient(*EDGE_FADE_FROM_START)
                val lineSize = if (alongBottom) Size(size.width, line) else Size(line, size.height)
                val lineAt = if (alongBottom) Offset(0f, size.height - line) else Offset.Zero
                onDrawBehind {
                    val segments = glow.segments
                    val pulse = breath?.value ?: 0f
                    drawRect(edgeBrush(segments, EDGE_WASH_ALPHA * (1f - BREATH_DEPTH * pulse), alongBottom))
                    drawRect(mask, blendMode = BlendMode.DstIn)
                    drawRect(edgeBrush(segments, OUTLINE_MAX_ALPHA * (1f - BREATH_DEPTH * 0.5f * pulse), alongBottom), lineAt, lineSize)
                }
            },
    )
}

/**
 * The segments side by side along the edge (left to right, or top to bottom), each solid in its
 * middle and blending into its neighbors.
 */
private fun edgeBrush(segments: Array<GlowSegment>, alphaScale: Float, horizontal: Boolean): Brush {
    val count = segments.size
    if (count == 1) return SolidColor(segments[0].glowColor(alphaScale))
    val stops = arrayOfNulls<Pair<Float, Color>>(count * 2)
    val step = 1f / count
    val edge = step * BLEND_SHARE
    var s = 0
    for (i in 0 until count) {
        val c = segments[i].glowColor(alphaScale)
        val start = i * step
        stops[s++] = (if (i == 0) 0f else start + edge) to c
        stops[s++] = (if (i == count - 1) 1f else start + step - edge) to c
    }
    @Suppress("UNCHECKED_CAST")
    val typed = stops as Array<Pair<Float, Color>>
    return if (horizontal) Brush.horizontalGradient(*typed) else Brush.verticalGradient(*typed)
}

/* Press: squish while held, elastic bounce on release */

/**
 * A card's press scale: shrinks to [PRESSED_SCALE] with a firm spring while held and springs back
 * past full size on release. A tap too quick to show the squish still dips first, so every tap
 * bounces. [onPress] runs as the finger lands (the press haptic). Apply it with [pressScale].
 */
@Composable
fun rememberPressScale(
    interactionSource: InteractionSource,
    enabled: Boolean,
    onPress: () -> Unit,
): Animatable<Float, AnimationVector1D> {
    val scale = remember { Animatable(1f) }
    val currentOnPress by rememberUpdatedState(onPress)
    LaunchedEffect(interactionSource, enabled) {
        if (!enabled) {
            scale.snapTo(1f)
            return@LaunchedEffect
        }
        interactionSource.interactions.collectLatest { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    currentOnPress()
                    scale.animateTo(PRESSED_SCALE, PressSpring)
                }
                is PressInteraction.Release -> {
                    if (scale.value > PRESSED_SCALE + QUICK_TAP_SLACK) scale.animateTo(PRESSED_SCALE, PressSpring)
                    scale.animateTo(1f, ReleaseSpring)
                }
                is PressInteraction.Cancel -> scale.animateTo(1f, ReleaseSpring)
            }
        }
    }
    return scale
}

/** Scales by [scale], read at draw time (no recomposition), the glow included when it comes after this. */
fun Modifier.pressScale(scale: Animatable<Float, AnimationVector1D>): Modifier = graphicsLayer {
    val value = scale.value
    scaleX = value
    scaleY = value
}

/* Tuning */

/** How far the edge's light reaches into the screen: about 2.5× a card's glow. */
private val EDGE_DEPTH = 36.dp

/** The wash over content is kept softer than a card's glow so text under it stays readable. */
private const val EDGE_WASH_ALPHA = 0.7f

/** Mask for the wash along the bottom: clear at the top, full at the edge, curving like a glow. */
private val EDGE_FADE = arrayOf(
    0f to Color.Transparent,
    0.5f to Color.Black.copy(alpha = 0.12f),
    0.8f to Color.Black.copy(alpha = 0.4f),
    1f to Color.Black,
)

/** The same fade for the rail, full at the start edge. */
private val EDGE_FADE_FROM_START = arrayOf(
    0f to Color.Black,
    0.2f to Color.Black.copy(alpha = 0.4f),
    0.5f to Color.Black.copy(alpha = 0.12f),
    1f to Color.Transparent,
)

private val GLOW_RADIUS = 14.dp
private val GLOW_RADIUS_BREATH = 4.dp
private val GLOW_SPREAD = 2.dp
private val OUTLINE_WIDTH = 2.dp

/** The outline is the solid edge of the light, so it's nearly opaque. */
private const val OUTLINE_MAX_ALPHA = 1f

/** The glow never gets fully opaque, so it reads as light rather than a solid border. */
private const val GLOW_MAX_ALPHA = 0.85f

/** Level for a stretch resting on its swatch color (nothing sent yet, or animations removed). */
private const val STATIC_LEVEL = 0.75f

private const val GLOW_IN_MS = 400
private const val MIN_GLOW_FADE_MS = 120
private const val FLASH_FADE_MS = 150
private const val FLASH_MAX_RISE = 0.35f
private const val DARK_BRIGHTNESS = 0.001f

private const val BREATH_MS = 4_000
private const val BREATH_DEPTH = 0.18f

/** Share of each segment's arc (at each end) spent blending into its neighbor. */
private const val BLEND_SHARE = 0.2f

private const val PRESSED_SCALE = 0.95f
private const val QUICK_TAP_SLACK = 0.02f
private val PressSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
private val ReleaseSpring = spring<Float>(dampingRatio = 0.4f, stiffness = Spring.StiffnessMediumLow)
