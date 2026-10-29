package com.dungeonvibes.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/* Effect settings (FX_REQUIREMENTS.md → The layers) */

// Times are whole milliseconds and levels are 0–1, like the rest of the scene data.
// Each layer keeps its settings while switched off (enabled = false), so toggling never loses them.

/** How a layer's timing relates across the bulbs of one group. */
enum class Spread { TOGETHER, INDEPENDENT, WAVE }

/**
 * Color shift: walk the palette in order (looping), pick at random, or go back and forth
 * (ping-pong: 1, 2, 3, 2, 1, 2…).
 */
enum class ColorOrder { IN_SEQUENCE, RANDOM, PING_PONG }

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
    /** Top of the peak brightness range. In a Flicker burst, the main pulse reaches it. */
    val peak: Float = 1f,
    /** Bottom of the peak brightness range (added in database version 3). */
    @ColumnInfo(defaultValue = "1.0") val peakMin: Float = 1f,
    val attackMs: Int = 50,
    val holdMs: Int = 100,
    val releaseMs: Int = 300,
    /** From the end of one flash (or burst) to the start of the next. */
    val gapMinMs: Int = 5_000,
    val gapMaxMs: Int = 20_000,
    /**
     * Single or Flicker is stored here: 1–1 is Single, anything higher is Flicker (always 2–3
     * pulses, see [flicker]). Kept as two columns so no migration was needed.
     */
    val pulsesMin: Int = 1,
    val pulsesMax: Int = 1,
    /** No longer used by Flashes (Flicker spacing is fixed); kept so old rows still load. */
    val spacingMinMs: Int = 100,
    val spacingMaxMs: Int = 300,
) {
    /**
     * Flicker: each flash is a quick burst of 2–3 pulses a few milliseconds apart, one full-strength
     * and the rest faint (owner request). Single: one pulse per flash.
     */
    val flicker: Boolean get() = pulsesMax > 1

    fun withFlicker(on: Boolean): FlashSettings =
        if (on) copy(pulsesMin = FLICKER_PULSES_MIN, pulsesMax = FLICKER_PULSES_MAX) else copy(pulsesMin = 1, pulsesMax = 1)

    companion object {
        const val FLICKER_PULSES_MIN = 2
        const val FLICKER_PULSES_MAX = 3
    }
}

const val DEFAULT_WAVE_OFFSET_MS = 500

/**
 * Spin (database version 10): a color that steps through the group's bulbs in their order
 * (Setup → Groups), lap after lap, each bulb lighting with an Attack-Hold-Release envelope as its
 * turn comes. It's laid over whatever the group shows (single color, Multi-color, Brightness
 * shift) and under Flashes. [stepMs] is the time from one bulb to the next; [lapGapMs] a pause
 * after the last bulb before the next lap. A release longer than a step leaves a trail.
 */
data class SpinSettings(
    val enabled: Boolean = false,
    @Embedded(prefix = "color_") val color: FxColor = FxColor.color(200f),
    /** 0–1: brightness at the top of each bulb's turn (brightest wins over what's underneath). */
    val level: Float = 1f,
    val attackMs: Int = 100,
    val holdMs: Int = 100,
    val releaseMs: Int = 500,
    val stepMs: Int = 300,
    val lapGapMs: Int = 0,
    /** Steps through the group's bulbs last to first (owner request; database version 11). */
    @ColumnInfo(defaultValue = "0") val reverse: Boolean = false,
)

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
    // Brightness shift, Flashes and Spin are layers since database version 13
    // (scene_effect_layers); these columns stay only so the table needn't be rebuilt.
    @Embedded(prefix = "brightnessShift_") val legacyBrightnessShift: BrightnessShiftSettings = BrightnessShiftSettings(),
    @Embedded(prefix = "flashes_") val legacyFlashes: FlashSettings = FlashSettings(),
    @Embedded(prefix = "spin_") val legacySpin: SpinSettings = SpinSettings(),
)

/** The kinds of layered effect a group can stack (owner request, Oct 23, 2026). */
enum class EffectLayerType { BRIGHTNESS_SHIFT, FLASHES, SPIN }

/**
 * One layered effect on one group in one scene (database version 13): any number per group, of
 * any kind, in priority order ([position] 0 is the top: it wins where layers overlap). Only the
 * settings for its [type] are used; the others keep their defaults. Belongs to its scene_lights
 * row, so deleting the scene or the group cascades. The `enabled` columns inside the settings are
 * unused here (a layer that exists plays).
 */
@Entity(
    tableName = "scene_effect_layers",
    foreignKeys = [
        ForeignKey(
            entity = SceneLightEntity::class,
            parentColumns = ["sceneId", "slotId"],
            childColumns = ["sceneId", "slotId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sceneId", "slotId"])],
)
data class EffectLayerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sceneId: Long,
    val slotId: Long,
    val position: Int,
    val type: EffectLayerType,
    @Embedded(prefix = "brightnessShift_") val brightnessShift: BrightnessShiftSettings = BrightnessShiftSettings(),
    @Embedded(prefix = "flashes_") val flashes: FlashSettings = FlashSettings(),
    @Embedded(prefix = "spin_") val spin: SpinSettings = SpinSettings(),
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
