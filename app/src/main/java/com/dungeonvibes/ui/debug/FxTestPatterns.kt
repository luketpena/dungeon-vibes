package com.dungeonvibes.ui.debug

import com.dungeonvibes.data.db.BrightnessMode
import com.dungeonvibes.data.db.BrightnessShiftSettings
import com.dungeonvibes.data.db.ColorOrder
import com.dungeonvibes.data.db.ColorShiftSettings
import com.dungeonvibes.data.db.FlashSettings
import com.dungeonvibes.data.db.FxColor
import com.dungeonvibes.data.db.Spread
import com.dungeonvibes.data.scene.GroupEffects
import com.dungeonvibes.light.LightState

/**
 * Hard-coded layer combinations for checking the engine on real bulbs (FX1 checklist). Debug
 * screen only: these are test patterns, not presets users will ever see.
 */
enum class FxTestPattern(
    val label: String,
    val base: LightState,
    val effects: GroupEffects,
) {
    FIRE(
        label = "Fire",
        base = LightState(hue = 25f, saturation = 1f, brightness = 0.7f, kelvin = null),
        effects = GroupEffects(
            colorShift = ColorShiftSettings(
                enabled = true,
                spread = Spread.INDEPENDENT,
                order = ColorOrder.RANDOM,
                blendMinMs = 400,
                blendMaxMs = 1_000,
                holdMinMs = 0,
                holdMaxMs = 100,
            ),
            palette = listOf(FxColor.color(12f), FxColor.color(25f), FxColor.color(36f, 0.95f)),
            brightnessShift = BrightnessShiftSettings(
                enabled = true,
                spread = Spread.INDEPENDENT,
                mode = BrightnessMode.FLICKER,
                levelMin = 0.35f,
                levelMax = 0.9f,
                speedMinMs = 200,
                speedMaxMs = 600,
            ),
        ),
    ),
    STORM(
        label = "Lightning",
        base = LightState(hue = 250f, saturation = 0.9f, brightness = 0.08f, kelvin = null),
        effects = GroupEffects(
            flashes = FlashSettings(
                enabled = true,
                spread = Spread.TOGETHER,
                color = FxColor.white(6_500),
                peak = 1f,
                attackMs = 30,
                holdMs = 60,
                releaseMs = 250,
                gapMinMs = 4_000,
                gapMaxMs = 10_000,
                pulsesMin = 1,
                pulsesMax = 1,
            ),
        ),
    ),
    ROLLING_STORM(
        label = "Rolling lightning (3 pulses)",
        base = LightState(hue = 250f, saturation = 0.9f, brightness = 0.08f, kelvin = null),
        effects = GroupEffects(
            flashes = FlashSettings(
                enabled = true,
                spread = Spread.TOGETHER,
                color = FxColor.white(6_500),
                peak = 1f,
                peakMin = 0.3f,
                attackMs = 20,
                holdMs = 40,
                releaseMs = 120,
                gapMinMs = 5_000,
                gapMaxMs = 10_000,
                pulsesMin = 3,
                pulsesMax = 3,
                spacingMinMs = 50,
                spacingMaxMs = 120,
            ),
        ),
    ),
    LIGHTNING_BUGS(
        label = "Lightning bugs",
        base = LightState(hue = 140f, saturation = 0.8f, brightness = 0.03f, kelvin = null),
        effects = GroupEffects(
            flashes = FlashSettings(
                enabled = true,
                spread = Spread.INDEPENDENT,
                color = FxColor.color(65f, 0.9f),
                peak = 0.6f,
                attackMs = 600,
                holdMs = 300,
                releaseMs = 900,
                gapMinMs = 1_500,
                gapMaxMs = 7_000,
            ),
        ),
    ),
    BREATHE(
        label = "Smooth breathing",
        base = LightState(hue = 200f, saturation = 0.9f, brightness = 0.5f, kelvin = null),
        effects = GroupEffects(
            brightnessShift = BrightnessShiftSettings(
                enabled = true,
                spread = Spread.TOGETHER,
                mode = BrightnessMode.SMOOTH,
                levelMin = 0.1f,
                levelMax = 0.8f,
                speedMinMs = 3_000,
                speedMaxMs = 5_000,
            ),
        ),
    ),
    DRIFT(
        label = "Color drift (incl. a white)",
        base = LightState(hue = 160f, saturation = 1f, brightness = 0.6f, kelvin = null),
        effects = GroupEffects(
            colorShift = ColorShiftSettings(
                enabled = true,
                spread = Spread.TOGETHER,
                order = ColorOrder.IN_SEQUENCE,
                blendMinMs = 3_000,
                blendMaxMs = 6_000,
                holdMinMs = 1_000,
                holdMaxMs = 2_000,
            ),
            palette = listOf(FxColor.color(160f), FxColor.color(120f), FxColor.color(280f, 0.8f), FxColor.white(4_000)),
        ),
    ),
    ;

    /** The same pattern with every layer's spread replaced (null keeps the pattern's own). */
    fun effectsWith(spread: Spread?): GroupEffects {
        if (spread == null) return effects
        return effects.copy(
            colorShift = effects.colorShift.copy(spread = spread),
            brightnessShift = effects.brightnessShift.copy(spread = spread),
            flashes = effects.flashes.copy(spread = spread),
        )
    }
}
