package com.dungeonvibes.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/* The FX library (FX_REQUIREMENTS.md → FX library and board) */

/** The one primitive an FX plays. */
enum class FxPrimitive { PULSE, FLICKER, STROBE, SPIN }

/** How an FX ends. */
enum class FxTransition {
    /** Back to whatever was running. */
    RETURN,

    /** Every bulb fades out over the FX's release. */
    LIGHTS_OFF,

    /** A library scene is applied with the FX's release as its fade. */
    SCENE,
}

/**
 * Pulse: the Flashes envelope as a one-shot burst. The channels say which parts of the light it
 * moves; at least one is on.
 */
data class PulseSettings(
    @Embedded(prefix = "color_") val color: FxColor = FxColor.white(6_500),
    val peakMin: Float = 1f,
    val peak: Float = 1f,
    val attackMs: Int = 50,
    val holdMs: Int = 300,
    val releaseMs: Int = 400,
    val pulsesMin: Int = 1,
    val pulsesMax: Int = 1,
    val spacingMinMs: Int = 100,
    val spacingMaxMs: Int = 300,
    val affectsColor: Boolean = true,
    val affectsSaturation: Boolean = true,
    val affectsBrightness: Boolean = true,
)

/** Flicker: a failing light, dipping by up to [depth] at random moments for [durationMs]. */
data class FlickerSettings(
    val durationMs: Int = 3_000,
    /** 0–1: the most a dip takes off the light's brightness. */
    val depth: Float = 0.9f,
    val intervalMinMs: Int = 100,
    val intervalMaxMs: Int = 400,
)

/**
 * Strobe (database version 10): even on/off flashes in a color for [durationMs], at
 * [flashesPerSecond] (capped at 4 a second; FX_REQUIREMENTS.md → Strobe). Off is dark.
 */
data class StrobeSettings(
    @Embedded(prefix = "color_") val color: FxColor = FxColor.white(6_500),
    /** 0–1: brightness while on. */
    val level: Float = 1f,
    val flashesPerSecond: Float = 3f,
    val durationMs: Int = 2_000,
)

/**
 * Spin as an FX (database version 10): a color stepping through the targets in order (room
 * order, or the one targeted group's bulb order) for [laps] laps, each bulb lighting with an
 * Attack-Hold-Release envelope as its turn comes, [stepMs] apart.
 */
data class SpinFxSettings(
    @Embedded(prefix = "color_") val color: FxColor = FxColor.color(270f),
    val level: Float = 1f,
    val attackMs: Int = 50,
    val holdMs: Int = 100,
    val releaseMs: Int = 400,
    val stepMs: Int = 200,
    val laps: Int = 2,
    /** Steps through the targets last to first (owner request; database version 11). */
    @ColumnInfo(defaultValue = "0") val reverse: Boolean = false,
)

@Entity(
    tableName = "fx",
    foreignKeys = [
        ForeignKey(
            entity = SceneEntity::class,
            parentColumns = ["id"],
            childColumns = ["transitionSceneId"],
            // Deleting the scene falls back to Return.
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("transitionSceneId")],
)
data class FxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sortOrder: Int,
    val primitive: FxPrimitive = FxPrimitive.PULSE,
    /** True: every bulb in a group, as one group. False: the groups in fx_targets. */
    val targetAll: Boolean = true,
    val spread: Spread = Spread.TOGETHER,
    val waveOffsetMs: Int = DEFAULT_WAVE_OFFSET_MS,
    val transition: FxTransition = FxTransition.RETURN,
    /** A library scene, for [FxTransition.SCENE]; null there means Return. */
    val transitionSceneId: Long? = null,
    @Embedded(prefix = "pulse_") val pulse: PulseSettings = PulseSettings(),
    @Embedded(prefix = "flicker_") val flicker: FlickerSettings = FlickerSettings(),
    @Embedded(prefix = "strobe_") val strobe: StrobeSettings = StrobeSettings(),
    @Embedded(prefix = "spin_") val spin: SpinFxSettings = SpinFxSettings(),
    val createdAt: Long,
    val updatedAt: Long,
)

/** One group an FX targets (when it doesn't target All lights). */
@Entity(
    tableName = "fx_targets",
    primaryKeys = ["fxId", "slotId"],
    foreignKeys = [
        ForeignKey(
            entity = FxEntity::class,
            parentColumns = ["id"],
            childColumns = ["fxId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = LightSlotEntity::class,
            parentColumns = ["id"],
            childColumns = ["slotId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("slotId")],
)
data class FxTargetEntity(
    val fxId: Long,
    val slotId: Long,
)

/**
 * One FX starred in one adventure (database version 10): the adventure's board shows its
 * favorites first, in [position] order, with the rest of the library a tap away.
 */
@Entity(
    tableName = "adventure_fx",
    primaryKeys = ["adventureId", "fxId"],
    foreignKeys = [
        ForeignKey(
            entity = AdventureEntity::class,
            parentColumns = ["id"],
            childColumns = ["adventureId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = FxEntity::class,
            parentColumns = ["id"],
            childColumns = ["fxId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("fxId")],
)
data class AdventureFxEntity(
    val adventureId: Long,
    val fxId: Long,
    val position: Int,
)
