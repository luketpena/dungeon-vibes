package com.dungeonvibes.data.scene

import com.dungeonvibes.data.db.BrightnessShiftSettings
import com.dungeonvibes.data.db.ColorShiftSettings
import com.dungeonvibes.data.db.EffectColorEntity
import com.dungeonvibes.data.db.FlashSettings
import com.dungeonvibes.data.db.FxColor
import com.dungeonvibes.data.db.SceneLightEffectsEntity

/** All effect layers of one group in one scene, with the Color shift palette in order. */
data class GroupEffects(
    val colorShift: ColorShiftSettings = ColorShiftSettings(),
    val palette: List<FxColor> = emptyList(),
    val brightnessShift: BrightnessShiftSettings = BrightnessShiftSettings(),
    val flashes: FlashSettings = FlashSettings(),
) {
    /** Color shift only runs with at least two colors (FX_REQUIREMENTS.md → Edge cases). */
    val runsColorShift: Boolean get() = colorShift.enabled && palette.size >= MIN_PALETTE

    val hasAny: Boolean get() = runsColorShift || brightnessShift.enabled || flashes.enabled

    companion object {
        const val MIN_PALETTE = 2
        const val MAX_PALETTE = 8
    }
}

internal fun SceneLightEffectsEntity.toGroupEffects(palette: List<FxColor>): GroupEffects =
    GroupEffects(colorShift, palette, brightnessShift, flashes)

/** Groups the scene's palette rows (already sorted by slot, then position) by slot. */
internal fun List<EffectColorEntity>.palettesBySlot(): Map<Long, List<FxColor>> {
    val count = size
    if (count == 0) return emptyMap()
    val result = HashMap<Long, MutableList<FxColor>>()
    for (i in 0 until count) {
        val row = this[i]
        result.getOrPut(row.slotId) { ArrayList(GroupEffects.MAX_PALETTE) }.add(row.color)
    }
    return result
}

/**
 * Effects of many scenes at once: scene id → slot id → effects. [colors] must be sorted by scene,
 * slot, then position (the DAO queries are).
 */
internal fun groupEffectsByScene(
    rows: List<SceneLightEffectsEntity>,
    colors: List<EffectColorEntity>,
): Map<Long, Map<Long, GroupEffects>> {
    val rowCount = rows.size
    if (rowCount == 0) return emptyMap()
    val palettes = HashMap<Long, HashMap<Long, MutableList<FxColor>>>()
    val colorCount = colors.size
    for (i in 0 until colorCount) {
        val c = colors[i]
        palettes.getOrPut(c.sceneId) { HashMap() }.getOrPut(c.slotId) { ArrayList(GroupEffects.MAX_PALETTE) }.add(c.color)
    }
    val result = HashMap<Long, HashMap<Long, GroupEffects>>()
    for (i in 0 until rowCount) {
        val row = rows[i]
        val palette = palettes[row.sceneId]?.get(row.slotId).orEmpty()
        result.getOrPut(row.sceneId) { HashMap() }[row.slotId] = row.toGroupEffects(palette)
    }
    return result
}
