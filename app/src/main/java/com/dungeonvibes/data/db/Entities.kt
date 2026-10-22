package com.dungeonvibes.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** One physical bulb. Matched by [protocolId] + [serial] (MAC), never by IP. */
@Entity(
    tableName = "bulbs",
    indices = [Index(value = ["protocolId", "serial"], unique = true)],
)
data class BulbEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val protocolId: String,
    val serial: String,
    val lastKnownIp: String,
    val name: String,
    val lastSeenAt: Long,
)

/**
 * A named role like "Ceiling". Scenes point at slots, never at bulbs.
 *
 * One hidden slot has [isRoom] set: it holds each scene's "All lights" settings (color, effects),
 * which every bulb shares while the scene's All lights is on. It never has bulbs and never shows in
 * Setup; storing it as a slot lets copy, reset, promote and effects treat it like any group.
 */
@Entity(tableName = "light_slots")
data class LightSlotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sortOrder: Int,
    @ColumnInfo(defaultValue = "0") val isRoom: Boolean = false,
)

/** Bulb-to-slot assignment. [bulbId] is the primary key, so a bulb is in at most one slot. */
@Entity(
    tableName = "slot_bulbs",
    primaryKeys = ["bulbId"],
    foreignKeys = [
        ForeignKey(
            entity = LightSlotEntity::class,
            parentColumns = ["id"],
            childColumns = ["slotId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = BulbEntity::class,
            parentColumns = ["id"],
            childColumns = ["bulbId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("slotId")],
)
data class SlotBulbEntity(
    val slotId: Long,
    val bulbId: Long,
)

@Entity(tableName = "adventures")
data class AdventureEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sortOrder: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * A library scene when [adventureId] is null, otherwise an adventure scene.
 * [parentSceneId] is the library scene it was copied from, used only for "Reset to library version".
 */
@Entity(
    tableName = "scenes",
    foreignKeys = [
        ForeignKey(
            entity = AdventureEntity::class,
            parentColumns = ["id"],
            childColumns = ["adventureId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SceneEntity::class,
            parentColumns = ["id"],
            childColumns = ["parentSceneId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("adventureId"), Index("parentSceneId")],
)
data class SceneEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val adventureId: Long?,
    val parentSceneId: Long?,
    val sortOrder: Int,
    val fadeMs: Int,
    val createdAt: Long,
    val updatedAt: Long,
    /**
     * "All lights": every bulb plays the scene's All-lights settings (the hidden room slot's row),
     * overriding, not changing, each group's own settings (owner request; database version 4).
     */
    @ColumnInfo(defaultValue = "0") val allLightsOn: Boolean = false,
    /**
     * The one hidden "default lighting" scene (a neutral room), edited from Setup and applied from
     * the lights menu. Never listed in the library (owner request; database version 8).
     */
    @ColumnInfo(defaultValue = "0") val isDefault: Boolean = false,
) {
    val isLibraryScene: Boolean get() = adventureId == null && !isDefault

    /** Rule 4: Reset shows only when a parent is set. SET_NULL guarantees a set parent exists. */
    val canResetToLibrary: Boolean get() = adventureId != null && parentSceneId != null
}

enum class LightMode { COLOR, WHITE }

/**
 * Settings a group gets before it has any in a scene: off, warm white at 2700 K (REQUIREMENTS:
 * white defaults to 2700 K), 80 % brightness, with a warm orange ready if switched to Color.
 * Constants, so the apply query can use them too.
 */
object LightDefaults {
    const val KELVIN = 2_700
    const val HUE = 30f
    const val SATURATION = 1f
    const val BRIGHTNESS = 0.8f
}

/**
 * One slot's settings in one scene. Color values (hue, saturation) and [kelvin] are both kept
 * regardless of [mode] and [isOn], so switching mode or turning a slot back on restores them (rule 7).
 */
@Entity(
    tableName = "scene_lights",
    primaryKeys = ["sceneId", "slotId"],
    foreignKeys = [
        ForeignKey(
            entity = SceneEntity::class,
            parentColumns = ["id"],
            childColumns = ["sceneId"],
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
data class SceneLightEntity(
    val sceneId: Long,
    val slotId: Long,
    val isOn: Boolean,
    val mode: LightMode,
    /** 0–360 */
    val hue: Float,
    /** 0–1 */
    val saturation: Float,
    val kelvin: Int,
    /** 0–1 */
    val brightness: Float,
)
