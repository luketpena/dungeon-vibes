package com.dungeonvibes.data.db

import androidx.room.Embedded

/** An adventure plus how many scenes it holds, for the Adventures list. */
data class AdventureWithSceneCount(
    @Embedded val adventure: AdventureEntity,
    val sceneCount: Int,
)

/**
 * What one bulb should do when a scene is applied (rule 8, with the precedence of database version
 * 9; see SceneResolver). [slotId] is the slot that decides its look: its individual light, the
 * All-lights slot, or the highest lit group it's in (for a bulb that's off, its highest group, so
 * a failure badge has somewhere to go). [isOn] is false when nothing lights it; the color fields
 * then hold the defaults and don't matter.
 */
data class BulbApplyTarget(
    val bulbId: Long,
    val protocolId: String,
    val serial: String,
    val lastKnownIp: String,
    val slotId: Long,
    val isOn: Boolean,
    val mode: LightMode?,
    val hue: Float?,
    val saturation: Float?,
    val kelvin: Int?,
    val brightness: Float?,
    /** The bulb's name, for display; not needed to reach the bulb. */
    val bulbName: String = "",
    /**
     * The bulb's place in each group it's in (group id → position), the order Wave and Spin
     * follow within that group.
     */
    val positions: Map<Long, Int> = emptyMap(),
    /** Every group the bulb is in, in group order (an FX aimed at any of them reaches it). */
    val groupIds: List<Long> = emptyList(),
    /**
     * The other lit slots that hold the bulb (lit groups below the winner, or the All-lights slot
     * under an individual light): their Flashes still reach it (owner decision).
     */
    val flashSlotIds: List<Long> = emptyList(),
)
