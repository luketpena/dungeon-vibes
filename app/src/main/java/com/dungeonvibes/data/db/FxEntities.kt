package com.dungeonvibes.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/* The FX library (FX_REQUIREMENTS.md → FX library and board) */

/** The one primitive an FX plays. Strobe and Spin arrive in FX4b. */
enum class FxPrimitive { PULSE, FLICKER }

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
