package com.dungeonvibes.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey

/* Effect settings (FX_REQUIREMENTS.md → The layers) */

// Times are whole milliseconds and levels are 0–1, like the rest of the scene data.
// Each layer keeps its settings while switched off (enabled = false), so toggling never loses them.

/** How a layer's timing relates across the bulbs of one group. */
enum class Spread { TOGETHER, INDEPENDENT, WAVE }

/** Color shift: walk the palette in order (looping) or pick at random. */
enum class ColorOrder { IN_SEQUENCE, RANDOM }

/** Brightness shift: eased breathing, or random jumps (flicker). */
enum class BrightnessMode { SMOOTH, FLICKER }

/**
 * One palette or flash color: a color (hue, saturation) or a white (kelvin), like a scene's base.
 * [brightness] is used by Multi-color palette entries only (database version 7); flash and pulse
 * colors take their brightness from their peak.
 */
data class FxColor(
    val mode: LightMode,
    /** 0–360 */
    val hue: Float,
    /** 0–1 */
    val saturation: Float,
    val kelvin: Int,
    /** 0–1 */
    @ColumnInfo(defaultValue = "1.0") val brightness: Float = 1f,
) {
    companion object {
        fun color(hue: Float, saturation: Float = 1f, brightness: Float = 1f): FxColor =
            FxColor(LightMode.COLOR, hue, saturation, DEFAULT_KELVIN, brightness)

        fun white(kelvin: Int): FxColor = FxColor(LightMode.WHITE, 0f, 0f, kelvin)

        private const val DEFAULT_KELVIN = 2700
    }
}

data class ColorShiftSettings(
    val enabled: Boolean = false,
    val spread: Spread = Spread.TOGETHER,
    val waveOffsetMs: Int = DEFAULT_WAVE_OFFSET_MS,
    val order: ColorOrder = ColorOrder.IN_SEQUENCE,
    val blendMinMs: Int = 2_000,
    val blendMaxMs: Int = 4_000,
    val holdMinMs: Int = 1_000,
    val holdMaxMs: Int = 3_000,
    /** Locked: every palette color shares one brightness (database version 7). */
    @ColumnInfo(defaultValue = "1") val brightnessLocked: Boolean = true,
)

data class BrightnessShiftSettings(
    val enabled: Boolean = false,
    val spread: Spread = Spread.TOGETHER,
    val waveOffsetMs: Int = DEFAULT_WAVE_OFFSET_MS,
    val mode: BrightnessMode = BrightnessMode.SMOOTH,
    val levelMin: Float = 0.2f,
    val levelMax: Float = 0.8f,
    /** Smooth: length of one full cycle. Flicker: time between changes. */
    val speedMinMs: Int = 3_000,
    val speedMaxMs: Int = 5_000,
)

data class FlashSettings(
    val enabled: Boolean = false,
    val spread: Spread = Spread.TOGETHER,
    val waveOffsetMs: Int = DEFAULT_WAVE_OFFSET_MS,
    @Embedded(prefix = "color_") val color: FxColor = FxColor.white(6_500),
    /** Top of the peak brightness range. In a burst of 2+, one random pulse reaches it. */
    val peak: Float = 1f,
    /** Bottom of the peak brightness range (added in database version 3). */
    @ColumnInfo(defaultValue = "1.0") val peakMin: Float = 1f,
    val attackMs: Int = 50,
    val holdMs: Int = 100,
    val releaseMs: Int = 300,
    /** From the end of one flash (or burst) to the start of the next. */
    val gapMinMs: Int = 5_000,
    val gapMaxMs: Int = 20_000,
    val pulsesMin: Int = 1,
    val pulsesMax: Int = 1,
    /** From the end of one pulse to the start of the next, within a burst. */
    val spacingMinMs: Int = 100,
    val spacingMaxMs: Int = 300,
)

const val DEFAULT_WAVE_OFFSET_MS = 500

/* Tables */

/**
 * The effect layers of one group in one scene. Exists only once effects have been set up for that
 * group; belongs to its scene_lights row, so deleting the scene or the group (light slot) cascades.
 */
@Entity(
    tableName = "scene_light_effects",
    primaryKeys = ["sceneId", "slotId"],
    foreignKeys = [
        ForeignKey(
            entity = SceneLightEntity::class,
            parentColumns = ["sceneId", "slotId"],
            childColumns = ["sceneId", "slotId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SceneLightEffectsEntity(
    val sceneId: Long,
    val slotId: Long,
    @Embedded(prefix = "colorShift_") val colorShift: ColorShiftSettings,
    @Embedded(prefix = "brightnessShift_") val brightnessShift: BrightnessShiftSettings,
    @Embedded(prefix = "flashes_") val flashes: FlashSettings,
)

/** One Color shift palette entry; [position] keeps the palette's order (0-based). */
@Entity(
    tableName = "scene_light_effect_colors",
    primaryKeys = ["sceneId", "slotId", "position"],
    foreignKeys = [
        ForeignKey(
            entity = SceneLightEffectsEntity::class,
            parentColumns = ["sceneId", "slotId"],
            childColumns = ["sceneId", "slotId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class EffectColorEntity(
    val sceneId: Long,
    val slotId: Long,
    val position: Int,
    @Embedded val color: FxColor,
)
