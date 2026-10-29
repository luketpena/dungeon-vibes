package com.dungeonvibes.ui.scenes.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dungeonvibes.R
import com.dungeonvibes.ui.common.FieldLabel
import com.dungeonvibes.ui.common.SteadySlider

/**
 * An Attack-Hold-Release envelope (owner request, Oct 23, 2026): a small chart of the curve with
 * the total length (A + H + R) in its top-right corner, over three side-by-side mini sliders,
 * each labelled above its left edge. Values are milliseconds, each 0–[maxMs].
 *
 * [color] draws the curve (the effect's own color, or null for the theme's); [releaseCurve] bends
 * the release like the effect plays it (0 = straight, see PulseShot.RELEASE_CURVE).
 */
@Composable
internal fun AhrControl(
    attackMs: Int,
    holdMs: Int,
    releaseMs: Int,
    maxMs: Int,
    onChange: (attackMs: Int, holdMs: Int, releaseMs: Int) -> Unit,
    color: Color? = null,
    releaseCurve: Float = 0f,
) {
    val curveColor = color ?: MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Box(Modifier.fillMaxWidth().height(CHART_HEIGHT)) {
            AhrChart(
                attackMs = attackMs,
                holdMs = holdMs,
                releaseMs = releaseMs,
                releaseCurve = releaseCurve,
                color = curveColor,
                modifier = Modifier.fillMaxWidth().height(CHART_HEIGHT),
            )
            Text(
                formatDuration(attackMs + holdMs + releaseMs),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(FIELD_GAP),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            MiniTimeSlider(R.string.fx_ahr_attack, attackMs, maxMs, Modifier.weight(1f)) {
                onChange(it, holdMs, releaseMs)
            }
            MiniTimeSlider(R.string.fx_ahr_hold, holdMs, maxMs, Modifier.weight(1f)) {
                onChange(attackMs, it, releaseMs)
            }
            MiniTimeSlider(R.string.fx_ahr_release, releaseMs, maxMs, Modifier.weight(1f)) {
                onChange(attackMs, holdMs, it)
            }
        }
    }
}

/**
 * The envelope as a line over a soft fill: up over the attack, flat through the hold, down over
 * the release, scaled so the whole effect fills the width. Dashed lines mark where each part ends.
 * The top [CHART_TOP_ROOM] is left for the total.
 */
@Composable
private fun AhrChart(
    attackMs: Int,
    holdMs: Int,
    releaseMs: Int,
    releaseCurve: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val baseline = MaterialTheme.colorScheme.outlineVariant
    val guide = MaterialTheme.colorScheme.outline
    Canvas(modifier) {
        val stroke = LINE_WIDTH.toPx()
        val half = stroke / 2f
        val top = CHART_TOP_ROOM.toPx()
        val bottom = size.height - half
        val left = half
        val width = size.width - stroke
        val total = attackMs + holdMs + releaseMs

        // The axis, always: a zero-length effect is just this flat line.
        drawLine(baseline, Offset(0f, bottom), Offset(size.width, bottom), strokeWidth = 1.dp.toPx())
        if (total <= 0) return@Canvas

        val scale = width / total
        val peakX = left + attackMs * scale
        val holdEndX = peakX + holdMs * scale
        val endX = left + width

        val line = Path().apply {
            moveTo(left, bottom)
            lineTo(peakX, top)
            lineTo(holdEndX, top)
            if (releaseCurve == 0f || releaseMs == 0) {
                lineTo(endX, bottom)
            } else {
                // Sampled, so the bend matches what the bulbs do.
                for (i in 1..RELEASE_SAMPLES) {
                    val x = i.toFloat() / RELEASE_SAMPLES
                    val level = 1f - x * (1f - releaseCurve + releaseCurve * x)
                    lineTo(holdEndX + (endX - holdEndX) * x, bottom - (bottom - top) * level)
                }
            }
        }
        val fill = Path().apply {
            addPath(line)
            lineTo(left, bottom)
            close()
        }
        drawPath(fill, color.copy(alpha = FILL_ALPHA))

        val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
        for (x in floatArrayOf(peakX, holdEndX)) {
            if (x <= left || x >= endX) continue
            drawLine(guide.copy(alpha = GUIDE_ALPHA), Offset(x, top), Offset(x, bottom), strokeWidth = 1.dp.toPx(), pathEffect = dash)
        }
        drawPath(line, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * One duration as a mini slider: its name above the left edge, the value at the right, on the same
 * squared scale and steps as the full-width time sliders (fine control for short times).
 */
@Composable
private fun MiniTimeSlider(
    labelRes: Int,
    valueMs: Int,
    maxMs: Int,
    modifier: Modifier = Modifier,
    onChange: (Int) -> Unit,
) {
    Column(modifier) {
        FieldLabel(stringResource(labelRes), formatDuration(valueMs), topPadding = 0.dp)
        SteadySlider(
            value = timeToPosition(valueMs, 0, maxMs),
            onValueChange = { position ->
                val ms = positionToTime(position, 0, maxMs)
                if (ms != valueMs) onChange(ms)
            },
        )
    }
}

/* Layout */
private val CHART_HEIGHT = 72.dp
private val CHART_TOP_ROOM = 22.dp
private val FIELD_GAP = 8.dp
private val LINE_WIDTH = 2.dp
private const val FILL_ALPHA = 0.18f
private const val GUIDE_ALPHA = 0.6f
private const val RELEASE_SAMPLES = 24
