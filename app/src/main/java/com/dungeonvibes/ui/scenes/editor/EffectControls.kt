package com.dungeonvibes.ui.scenes.editor

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dungeonvibes.R
import com.dungeonvibes.data.db.BrightnessMode
import com.dungeonvibes.data.db.BrightnessShiftSettings
import com.dungeonvibes.data.db.ColorOrder
import com.dungeonvibes.data.db.FxColor
import com.dungeonvibes.data.db.LightMode
import com.dungeonvibes.data.db.SceneLightEntity
import com.dungeonvibes.data.db.Spread
import com.dungeonvibes.data.scene.GroupEffects
import com.dungeonvibes.ui.common.GradientSlider
import com.dungeonvibes.ui.common.LightColors
import com.dungeonvibes.ui.common.SettingsCard
import com.dungeonvibes.ui.common.SteadyRangeSlider
import com.dungeonvibes.ui.common.SteadySlider
import com.dungeonvibes.ui.common.Swatch
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * The Effects section of a group's panel in the scene editor (FX_REQUIREMENTS.md → Scene editor).
 * Every control hands back a whole new GroupEffects; the ViewModel keeps the draft and saves it.
 */

/** Ranges for the time sliders, in ms (FX_REQUIREMENTS.md → The layers). */
internal object FxRanges {
    const val SHIFT_MAX = 60_000
    const val SMOOTH_MIN = 500
    const val SMOOTH_MAX = 60_000
    const val FLICKER_MIN = 100
    const val FLICKER_MAX = 5_000
    const val ENVELOPE_MAX = 10_000
    const val GAP_MIN = 200
    const val GAP_MAX = 300_000
    const val SPACING_MIN = 50
    const val SPACING_MAX = 2_000
    const val WAVE_MIN = 50
    const val WAVE_MAX = 10_000
    const val MAX_PULSES = 5

    /** Speeds a Brightness shift switches to when its mode changes (the spec's defaults). */
    val SMOOTH_DEFAULT = 3_000 to 5_000
    val FLICKER_DEFAULT = 150 to 600
}

@Composable
fun EffectsSection(
    row: SlotRowState,
    livePreview: Boolean,
    onChange: (GroupEffects) -> Unit,
    onFlashNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        Text(stringResource(R.string.fx_section), style = MaterialTheme.typography.titleSmall)
        val light = row.light
        if (light == null || !row.isOn) {
            Text(
                stringResource(R.string.fx_off_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            val effects = row.effects ?: GroupEffects()
            Spacer(Modifier.height(8.dp))
            BrightnessShiftCard(effects, onChange)
            Spacer(Modifier.height(8.dp))
            FlashesCard(effects, onChange, canFlashNow = livePreview && row.hasBulbs, onFlashNow = onFlashNow)
        }
    }
}

/* Layer cards */

/**
 * A group's Multi-color mode: the Color shift layer, shown in place of the single-color controls
 * (owner feedback: drifting through a palette is a way of coloring the group, not an add-on).
 * "Color" sets the palette's colors one at a time; "Behavior" sets how the group moves through it.
 */
@Composable
internal fun MultiColorControls(effects: GroupEffects, onChange: (GroupEffects) -> Unit) {
    val settings = effects.colorShift
    SettingsCard(stringResource(R.string.settings_color)) {
        PaletteEditor(effects.palette, settings.brightnessLocked) { palette, locked ->
            onChange(effects.copy(palette = palette, colorShift = settings.copy(brightnessLocked = locked)))
        }
    }
    Spacer(Modifier.height(8.dp))
    SettingsCard(stringResource(R.string.settings_behavior)) {
        Choice(
            options = listOf(ColorOrder.IN_SEQUENCE to R.string.fx_order_sequence, ColorOrder.RANDOM to R.string.fx_order_random),
            selected = settings.order,
            onSelect = { onChange(effects.copy(colorShift = settings.copy(order = it))) },
        )
        SpreadControl(settings.spread, settings.waveOffsetMs) { spread, offset ->
            onChange(effects.copy(colorShift = settings.copy(spread = spread, waveOffsetMs = offset)))
        }
        TimeRangeControl(R.string.fx_blend, settings.blendMinMs, settings.blendMaxMs, 0, FxRanges.SHIFT_MAX) { lo, hi ->
            onChange(effects.copy(colorShift = settings.copy(blendMinMs = lo, blendMaxMs = hi)))
        }
        TimeRangeControl(R.string.fx_hold, settings.holdMinMs, settings.holdMaxMs, 0, FxRanges.SHIFT_MAX) { lo, hi ->
            onChange(effects.copy(colorShift = settings.copy(holdMinMs = lo, holdMaxMs = hi)))
        }
    }
}

@Composable
private fun BrightnessShiftCard(effects: GroupEffects, onChange: (GroupEffects) -> Unit) {
    val settings = effects.brightnessShift
    val flicker = settings.mode == BrightnessMode.FLICKER
    LayerCard(
        title = stringResource(R.string.fx_brightness_shift),
        hint = stringResource(R.string.fx_brightness_shift_hint),
        enabled = settings.enabled,
        onEnabledChange = { onChange(effects.copy(brightnessShift = settings.copy(enabled = it))) },
    ) {
        Choice(
            options = listOf(BrightnessMode.SMOOTH to R.string.fx_mode_smooth, BrightnessMode.FLICKER to R.string.fx_mode_flicker),
            selected = settings.mode,
            onSelect = { mode -> onChange(effects.copy(brightnessShift = settings.withMode(mode))) },
        )
        PercentRangeControl(R.string.fx_brightness_range, settings.levelMin, settings.levelMax) { lo, hi ->
            onChange(effects.copy(brightnessShift = settings.copy(levelMin = lo, levelMax = hi)))
        }
        TimeRangeControl(
            labelRes = if (flicker) R.string.fx_change_every else R.string.fx_cycle,
            lowMs = settings.speedMinMs,
            highMs = settings.speedMaxMs,
            minMs = if (flicker) FxRanges.FLICKER_MIN else FxRanges.SMOOTH_MIN,
            maxMs = if (flicker) FxRanges.FLICKER_MAX else FxRanges.SMOOTH_MAX,
        ) { lo, hi -> onChange(effects.copy(brightnessShift = settings.copy(speedMinMs = lo, speedMaxMs = hi))) }
        SpreadControl(settings.spread, settings.waveOffsetMs) { spread, offset ->
            onChange(effects.copy(brightnessShift = settings.copy(spread = spread, waveOffsetMs = offset)))
        }
    }
}

@Composable
private fun FlashesCard(
    effects: GroupEffects,
    onChange: (GroupEffects) -> Unit,
    canFlashNow: Boolean,
    onFlashNow: () -> Unit,
) {
    val settings = effects.flashes
    LayerCard(
        title = stringResource(R.string.fx_flashes),
        hint = stringResource(R.string.fx_flashes_hint),
        enabled = settings.enabled,
        onEnabledChange = { onChange(effects.copy(flashes = settings.copy(enabled = it))) },
    ) {
        ControlLabel(stringResource(R.string.fx_flash_color))
        FxColorControls(settings.color) { onChange(effects.copy(flashes = settings.copy(color = it))) }
        PercentRangeControl(R.string.fx_peak, settings.peakMin, settings.peak) { lo, hi ->
            onChange(effects.copy(flashes = settings.copy(peakMin = lo, peak = hi)))
        }
        TimeControl(R.string.fx_attack, settings.attackMs, 0, FxRanges.ENVELOPE_MAX) {
            onChange(effects.copy(flashes = settings.copy(attackMs = it)))
        }
        TimeControl(R.string.fx_hold, settings.holdMs, 0, FxRanges.ENVELOPE_MAX) {
            onChange(effects.copy(flashes = settings.copy(holdMs = it)))
        }
        TimeControl(R.string.fx_release, settings.releaseMs, 0, FxRanges.ENVELOPE_MAX) {
            onChange(effects.copy(flashes = settings.copy(releaseMs = it)))
        }
        TimeRangeControl(R.string.fx_gap, settings.gapMinMs, settings.gapMaxMs, FxRanges.GAP_MIN, FxRanges.GAP_MAX) { lo, hi ->
            onChange(effects.copy(flashes = settings.copy(gapMinMs = lo, gapMaxMs = hi)))
        }
        CountRangeControl(R.string.fx_pulses, settings.pulsesMin, settings.pulsesMax, 1, FxRanges.MAX_PULSES) { lo, hi ->
            onChange(effects.copy(flashes = settings.copy(pulsesMin = lo, pulsesMax = hi)))
        }
        if (settings.pulsesMax > 1) {
            TimeRangeControl(
                R.string.fx_spacing,
                settings.spacingMinMs,
                settings.spacingMaxMs,
                FxRanges.SPACING_MIN,
                FxRanges.SPACING_MAX,
            ) { lo, hi -> onChange(effects.copy(flashes = settings.copy(spacingMinMs = lo, spacingMaxMs = hi))) }
        }
        SpreadControl(settings.spread, settings.waveOffsetMs) { spread, offset ->
            onChange(effects.copy(flashes = settings.copy(spread = spread, waveOffsetMs = offset)))
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onFlashNow, enabled = canFlashNow) { Text(stringResource(R.string.fx_flash_now)) }
            if (!canFlashNow) {
                Text(
                    stringResource(R.string.fx_flash_now_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}

/** A layer: title and on/off switch; its controls show only while it's on (settings are kept). */
@Composable
private fun LayerCard(
    title: String,
    hint: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    SettingsCard(title = title, hint = hint, enabled = enabled, onEnabledChange = onEnabledChange, content = content)
}

/* Palette and colors */

/**
 * The palette: its swatches (tap one to edit it; + adds one), then the chosen color's hue,
 * saturation and brightness. Palette colors are always colors (no white mode); a white stored
 * before version 7 becomes a color when its hue or saturation is first moved. With the brightness
 * [locked], every color shares the brightness slider; locking snaps them all to the chosen one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PaletteEditor(palette: List<FxColor>, locked: Boolean, onChange: (List<FxColor>, Boolean) -> Unit) {
    if (palette.isEmpty()) return
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val index = selected.coerceIn(0, palette.lastIndex)
    val color = palette[index]
    val ring = MaterialTheme.colorScheme.primary
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        val count = palette.size
        for (i in 0 until count) {
            val chosen = i == index
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .then(if (chosen) Modifier.border(2.dp, ring, CircleShape) else Modifier)
                    .clickable { selected = i },
            ) {
                Swatch(LightColors.displayColor(palette[i]), 34.dp)
            }
        }
        if (count < GroupEffects.MAX_PALETTE) {
            IconButton(onClick = {
                onChange(palette + neighbour(color), locked)
                selected = count
            }) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.fx_add_color))
            }
        }
    }
    // Right under the swatches, so it reads as part of the palette (it removes the ringed one).
    if (palette.size > GroupEffects.MIN_PALETTE) {
        TextButton(onClick = {
            onChange(palette.filterIndexed { i, _ -> i != index }, locked)
            selected = (index - 1).coerceAtLeast(0)
        }) { Text(stringResource(R.string.fx_remove_color)) }
    }

    fun update(changed: FxColor) {
        val updated = palette.toMutableList()
        updated[index] = changed
        onChange(updated, locked)
    }

    ControlLabel(stringResource(R.string.scene_hue))
    GradientSlider(color.hue, { update(color.copy(mode = LightMode.COLOR, hue = it)) }, 0f..360f, LightColors.HueBrush)
    ControlLabel(stringResource(R.string.scene_saturation))
    val saturationBrush = remember(color.hue) { LightColors.saturationBrush(color.hue) }
    GradientSlider(color.saturation, { update(color.copy(mode = LightMode.COLOR, saturation = it)) }, 0f..1f, saturationBrush)
    ControlLabel(stringResource(R.string.scene_brightness))
    Row(verticalAlignment = Alignment.CenterVertically) {
        val brightnessBrush = remember(color.hue, color.saturation, color.mode, color.kelvin) {
            LightColors.brightnessBrush(LightColors.displayColor(color.copy(brightness = 1f)))
        }
        GradientSlider(
            value = color.brightness,
            onValueChange = { level ->
                if (locked) {
                    onChange(palette.map { it.copy(brightness = level) }, true)
                } else {
                    update(color.copy(brightness = level))
                }
            },
            valueRange = 0f..1f,
            brush = brightnessBrush,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = {
            // Locking snaps every color to the chosen one's brightness.
            if (locked) {
                onChange(palette, false)
            } else {
                onChange(palette.map { it.copy(brightness = color.brightness) }, true)
            }
        }) {
            Icon(
                painterResource(if (locked) R.drawable.ic_lock else R.drawable.ic_lock_open),
                contentDescription = stringResource(if (locked) R.string.palette_brightness_locked else R.string.palette_brightness_unlocked),
                tint = if (locked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Color or white, with the same sliders as a group's base (no brightness: layers set that). */
@Composable
internal fun FxColorControls(color: FxColor, onChange: (FxColor) -> Unit) {
    Choice(
        options = listOf(LightMode.COLOR to R.string.scene_mode_color, LightMode.WHITE to R.string.scene_mode_white),
        selected = color.mode,
        onSelect = { mode ->
            // Keep both sets of values, like the base controls (rule 7).
            onChange(color.copy(mode = mode, saturation = if (mode == LightMode.COLOR && color.saturation == 0f) 1f else color.saturation))
        },
    )
    if (color.mode == LightMode.COLOR) {
        ControlLabel(stringResource(R.string.scene_hue))
        GradientSlider(color.hue, { onChange(color.copy(hue = it)) }, 0f..360f, LightColors.HueBrush)
        ControlLabel(stringResource(R.string.scene_saturation))
        val brush = remember(color.hue) { LightColors.saturationBrush(color.hue) }
        GradientSlider(color.saturation, { onChange(color.copy(saturation = it)) }, 0f..1f, brush)
    } else {
        ControlLabel(stringResource(R.string.scene_temperature, color.kelvin))
        GradientSlider(
            value = color.kelvin.toFloat(),
            onValueChange = { onChange(color.copy(kelvin = it.roundToInt())) },
            valueRange = LightColors.MIN_KELVIN.toFloat()..LightColors.MAX_KELVIN.toFloat(),
            brush = LightColors.KelvinBrush,
        )
    }
}

/**
 * The group's own color (a white group's stored hue, fully saturated, since palettes hold colors
 * only), then one a little further round the wheel; both at the group's brightness.
 */
internal fun defaultPalette(base: SceneLightEntity): List<FxColor> {
    val saturation = if (base.mode == LightMode.WHITE) 1f else base.saturation
    val first = FxColor.color(base.hue, saturation, base.brightness)
    return listOf(first, neighbour(first))
}

/** A new palette color next to [color]: 40° on, at the same brightness (a white becomes a color). */
private fun neighbour(color: FxColor): FxColor = FxColor.color(
    hue = (color.hue + 40f) % 360f,
    saturation = if (color.mode == LightMode.WHITE || color.saturation == 0f) 1f else color.saturation,
    brightness = color.brightness,
)

/** Switching mode swaps in that mode's speed defaults: a 3 s cycle makes a poor flicker and vice versa. */
private fun BrightnessShiftSettings.withMode(mode: BrightnessMode): BrightnessShiftSettings {
    if (mode == this.mode) return this
    val (lo, hi) = if (mode == BrightnessMode.FLICKER) FxRanges.FLICKER_DEFAULT else FxRanges.SMOOTH_DEFAULT
    return copy(mode = mode, speedMinMs = lo, speedMaxMs = hi)
}

/* Generic controls */

@Composable
internal fun SpreadControl(spread: Spread, waveOffsetMs: Int, onChange: (Spread, Int) -> Unit) {
    ControlLabel(stringResource(R.string.fx_spread))
    Choice(
        options = listOf(
            Spread.TOGETHER to R.string.fx_spread_together,
            Spread.INDEPENDENT to R.string.fx_spread_independent,
            Spread.WAVE to R.string.fx_spread_wave,
        ),
        selected = spread,
        onSelect = { onChange(it, waveOffsetMs) },
    )
    if (spread == Spread.WAVE) {
        TimeControl(R.string.fx_wave_delay, waveOffsetMs, FxRanges.WAVE_MIN, FxRanges.WAVE_MAX) { onChange(spread, it) }
    }
}

@Composable
internal fun <T> Choice(options: List<Pair<T, Int>>, selected: T, onSelect: (T) -> Unit) {
    val count = options.size
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        for (i in 0 until count) {
            val (value, label) = options[i]
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = count),
            ) { Text(stringResource(label)) }
        }
    }
}

/** One duration on a squared scale, so short times get most of the slider. */
@Composable
internal fun TimeControl(labelRes: Int, valueMs: Int, minMs: Int, maxMs: Int, onChange: (Int) -> Unit) {
    ControlLabel(stringResource(labelRes, formatDuration(valueMs)))
    SteadySlider(
        value = timeToPosition(valueMs, minMs, maxMs),
        onValueChange = { onChange(positionToTime(it, minMs, maxMs)) },
    )
}

/** A min–max duration on a squared scale. */
@Composable
internal fun TimeRangeControl(
    labelRes: Int,
    lowMs: Int,
    highMs: Int,
    minMs: Int,
    maxMs: Int,
    onChange: (Int, Int) -> Unit,
) {
    val text = if (lowMs == highMs) formatDuration(lowMs) else formatDuration(lowMs) + " – " + formatDuration(highMs)
    ControlLabel(stringResource(labelRes, text))
    SteadyRangeSlider(
        value = timeToPosition(lowMs, minMs, maxMs)..timeToPosition(highMs, minMs, maxMs),
        onValueChange = { onChange(positionToTime(it.start, minMs, maxMs), positionToTime(it.endInclusive, minMs, maxMs)) },
    )
}

@Composable
internal fun PercentRangeControl(labelRes: Int, low: Float, high: Float, onChange: (Float, Float) -> Unit) {
    ControlLabel(stringResource(labelRes, (low * 100).roundToInt(), (high * 100).roundToInt()))
    SteadyRangeSlider(
        value = low..high,
        onValueChange = { onChange(snapPercent(it.start), snapPercent(it.endInclusive)) },
    )
}

@Composable
internal fun CountRangeControl(labelRes: Int, low: Int, high: Int, min: Int, max: Int, onChange: (Int, Int) -> Unit) {
    ControlLabel(stringResource(labelRes, if (low == high) "$low" else "$low – $high"))
    SteadyRangeSlider(
        value = low.toFloat()..high.toFloat(),
        onValueChange = { onChange(it.start.roundToInt(), it.endInclusive.roundToInt()) },
        valueRange = min.toFloat()..max.toFloat(),
        steps = max - min - 1,
    )
}

@Composable
internal fun ControlLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/* Scales and formatting */

internal fun timeToPosition(ms: Int, minMs: Int, maxMs: Int): Float =
    sqrt(((ms - minMs).toFloat() / (maxMs - minMs)).coerceIn(0f, 1f))

/** Slider position to ms, snapped to a step that suits the size: 10 ms, 100 ms, then whole seconds. */
internal fun positionToTime(position: Float, minMs: Int, maxMs: Int): Int {
    val p = position.coerceIn(0f, 1f)
    val raw = minMs + (maxMs - minMs) * p * p
    val step = when {
        raw < 1_000f -> 10
        raw < 10_000f -> 100
        else -> 1_000
    }
    return ((raw / step).roundToInt() * step).coerceIn(minMs, maxMs)
}

private fun snapPercent(value: Float): Float = (value * 100f).roundToInt() / 100f

/** "0.05 s", "1.5 s", "12 s", "2 min 30 s". */
internal fun formatDuration(ms: Int): String = when {
    ms < 1_000 -> String.format(Locale.getDefault(), "%.2f s", ms / 1_000f)
    ms < 10_000 -> String.format(Locale.getDefault(), "%.1f s", ms / 1_000f)
    ms < 60_000 -> "${ms / 1_000} s"
    ms % 60_000 < 1_000 -> "${ms / 60_000} min"
    else -> "${ms / 60_000} min ${(ms % 60_000) / 1_000} s"
}
