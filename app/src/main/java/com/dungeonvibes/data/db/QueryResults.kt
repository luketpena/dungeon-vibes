package com.dungeonvibes.data.db

import androidx.room.Embedded

/** An adventure plus how many scenes it holds, for the Adventures list. */
data class AdventureWithSceneCount(
    @Embedded val adventure: AdventureEntity,
    val sceneCount: Int,
)

/**
 * What one assigned bulb should do when a scene is applied (rule 8).
 * [isOn] is false when the scene has no row for the bulb's slot or the row is switched off;
 * the color fields are then null or irrelevant.
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
    /** For ordering bulbs (Wave follows names); not needed to reach the bulb. */
    val bulbName: String = "",
)
