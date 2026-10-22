package com.dungeonvibes.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Sliders that only move on a deliberate sideways drag (owner feedback: Material sliders got bumped
 * while scrolling). Touching the track does nothing, so a scroll that starts on a slider just
 * scrolls; the value changes only once the finger has clearly moved sideways, and then the thumb
 * follows the finger from where it was rather than jumping to it.
 */

/** A single-value slider; [trackBrush] draws a gradient track (hue, temperature...). */
@Composable
fun SteadySlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    trackBrush: Brush? = null,
    enabled: Boolean = true,
) {
    SliderCore(
        first = value,
        second = null,
        onChange = { v, _ -> onValueChange(v) },
        modifier = modifier,
        valueRange = valueRange,
        steps = steps,
        trackBrush = trackBrush,
        enabled = enabled,
    )
}

/** A min–max slider; the thumb nearest the finger moves, and the two never cross. */
@Composable
fun SteadyRangeSlider(
    value: ClosedFloatingPointRange<Float>,
    onValueChange: (ClosedFloatingPointRange<Float>) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
) {
    SliderCore(
        first = value.start,
        second = value.endInclusive,
        onChange = { lo, hi -> onValueChange(lo..hi) },
        modifier = modifier,
        valueRange = valueRange,
        steps = steps,
        trackBrush = null,
        enabled = enabled,
    )
}

@Composable
private fun SliderCore(
    first: Float,
    second: Float?,
    onChange: (Float, Float) -> Unit,
    modifier: Modifier,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    trackBrush: Brush?,
    enabled: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val primary = colors.primary
    val inactive = colors.surfaceVariant
    val thumbRing = colors.surface
    val focusManager = LocalFocusManager.current
    val haptics = LocalHapticFeedback.current

    /** Which thumb is being dragged: -1 none, 0 the first (or only), 1 the second. */
    var dragging by remember { mutableIntStateOf(-1) }

    // The gesture below lives across recompositions; these always hold the newest values.
    val latestFirst by rememberUpdatedState(first)
    val latestSecond by rememberUpdatedState(second)
    val latestOnChange by rememberUpdatedState(onChange)

    val start = valueRange.start
    val span = (valueRange.endInclusive - start).takeIf { it > 0f } ?: 1f

    Canvas(
        modifier
            .fillMaxWidth()
            .height(SLIDER_HEIGHT)
            .alpha(if (enabled) 1f else 0.38f)
            .semantics {
                if (second == null) {
                    progressBarRangeInfo = ProgressBarRangeInfo(first, valueRange, steps)
                    if (enabled) {
                        setProgress { target ->
                            latestOnChange(snap(target.coerceIn(valueRange), valueRange, steps), 0f)
                            true
                        }
                    }
                }
            }
            .pointerInput(enabled, valueRange, steps) {
                if (!enabled) return@pointerInput
                val radius = THUMB_RADIUS.toPx()
                var thumb = 0
                var position = 0f
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val usable = size.width - 2 * radius
                        val x1 = radius + (latestFirst - start) / span * usable
                        val lastSecond = latestSecond
                        thumb = if (lastSecond == null) {
                            0
                        } else {
                            val x2 = radius + (lastSecond - start) / span * usable
                            val d1 = abs(offset.x - x1)
                            val d2 = abs(offset.x - x2)
                            // Thumbs on top of each other: the side the finger is on decides.
                            if (d1 == d2) (if (offset.x > x1) 1 else 0) else if (d1 < d2) 0 else 1
                        }
                        position = if (thumb == 0 || lastSecond == null) x1 else radius + (lastSecond - start) / span * usable
                        dragging = thumb
                        focusManager.clearFocus()
                        haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                    },
                    onDragEnd = { dragging = -1 },
                    onDragCancel = { dragging = -1 },
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        val usable = size.width - 2 * radius
                        position = (position + dx).coerceIn(radius, size.width - radius)
                        val value = snap(start + (position - radius) / usable * span, valueRange, steps)
                        val lastSecond = latestSecond
                        when {
                            lastSecond == null -> latestOnChange(value, 0f)
                            thumb == 0 -> latestOnChange(minOf(value, lastSecond), lastSecond)
                            else -> latestOnChange(latestFirst, maxOf(value, latestFirst))
                        }
                    },
                )
            },
    ) {
        val radius = THUMB_RADIUS.toPx()
        val usable = size.width - 2 * radius
        val cy = size.height / 2
        val x1 = radius + ((first - start) / span).coerceIn(0f, 1f) * usable

        /* Track */

        if (trackBrush != null) {
            val h = GRADIENT_TRACK.toPx()
            drawRoundRect(trackBrush, Offset(0f, cy - h / 2), Size(size.width, h), CornerRadius(h / 2))
        } else {
            val h = PLAIN_TRACK.toPx()
            drawRoundRect(inactive, Offset(0f, cy - h / 2), Size(size.width, h), CornerRadius(h / 2))
            val from = if (second == null) 0f else x1
            val to = if (second == null) x1 else radius + ((second - start) / span).coerceIn(0f, 1f) * usable
            drawRoundRect(primary, Offset(from, cy - h / 2), Size(to - from, h), CornerRadius(h / 2))
        }

        /* Thumbs */

        val grow = DRAG_GROWTH.toPx()
        val ring = THUMB_RING.toPx()
        fun thumb(x: Float, active: Boolean) {
            val r = radius + if (active) grow else 0f
            drawCircle(primary, r, Offset(x, cy))
            drawCircle(thumbRing, r, Offset(x, cy), style = Stroke(ring))
        }
        thumb(x1, dragging == 0)
        if (second != null) {
            thumb(radius + ((second - start) / span).coerceIn(0f, 1f) * usable, dragging == 1)
        }
    }
}

private fun snap(value: Float, range: ClosedFloatingPointRange<Float>, steps: Int): Float {
    if (steps <= 0) return value.coerceIn(range)
    val start = range.start
    val stepSize = (range.endInclusive - start) / (steps + 1)
    return (start + ((value - start) / stepSize).roundToInt() * stepSize).coerceIn(range)
}

private val SLIDER_HEIGHT = 44.dp
private val THUMB_RADIUS = 10.dp
private val DRAG_GROWTH = 3.dp
private val THUMB_RING = 2.dp
private val GRADIENT_TRACK = 14.dp
private val PLAIN_TRACK = 4.dp
