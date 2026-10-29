package com.dungeonvibes.data.scene

import com.dungeonvibes.data.db.BrightnessShiftSettings
import com.dungeonvibes.data.db.ColorShiftSettings
import com.dungeonvibes.data.db.EffectColorEntity
import com.dungeonvibes.data.db.EffectLayerEntity
import com.dungeonvibes.data.db.EffectLayerType
import com.dungeonvibes.data.db.FlashSettings
import com.dungeonvibes.data.db.FxColor
import com.dungeonvibes.data.db.SceneLightEffectsEntity
import com.dungeonvibes.data.db.SpinSettings

/**
 * One layered effect (owner request, Oct 23, 2026): a Brightness shift, Flashes or Spin with its
 * settings (only those of its [type] matter). [id] is its database row, or negative for a layer
 * added in the editor and not yet reloaded (it identifies the layer while editing). Equality
 * ignores [id]: two layers are the same effect when their settings are, so saving (which gives
 * new rows their ids) never counts as a change, and running effects don't restart over it.
 */
data class EffectLayer(
    val id: Long,
    val type: EffectLayerType,
    val brightnessShift: BrightnessShiftSettings = BrightnessShiftSettings(),
    val flashes: FlashSettings = FlashSettings(),
    val spin: SpinSettings = SpinSettings(),
) {
    override fun equals(other: Any?): Boolean =
        other is EffectLayer && type == other.type && brightnessShift == other.brightnessShift &&
            flashes == other.flashes && spin == other.spin

    override fun hashCode(): Int = ((type.hashCode() * 31 + brightnessShift.hashCode()) * 31 + flashes.hashCode()) * 31 + spin.hashCode()

    companion object {
        fun brightness(settings: BrightnessShiftSettings, id: Long = 0): EffectLayer =
            EffectLayer(id, EffectLayerType.BRIGHTNESS_SHIFT, brightnessShift = settings)

        fun flashes(settings: FlashSettings, id: Long = 0): EffectLayer = EffectLayer(id, EffectLayerType.FLASHES, flashes = settings)

        fun spin(settings: SpinSettings, id: Long = 0): EffectLayer = EffectLayer(id, EffectLayerType.SPIN, spin = settings)
    }
}

/**
 * All effects of one group in one scene: Multi-color (Color shift and its palette, in order) and
 * the layered effects in priority order (first is the top: where layers overlap, it wins).
 */
data class GroupEffects(
    val colorShift: ColorShiftSettings = ColorShiftSettings(),
    val palette: List<FxColor> = emptyList(),
    val layers: List<EffectLayer> = emptyList(),
) {
    /** Color shift only runs with at least two colors (FX_REQUIREMENTS.md → Edge cases). */
    val runsColorShift: Boolean get() = colorShift.enabled && palette.size >= MIN_PALETTE

    val hasAny: Boolean get() = runsColorShift || layers.isNotEmpty()

    /**
     * Whether the group has effects in the ✦ sense: any layered effect. Multi-color is a way of
     * coloring the group (owner decision), so it doesn't count, though it animates.
     */
    val hasEffectLayers: Boolean get() = layers.isNotEmpty()

    /** Whether any layer is Flashes (they also play on bulbs a higher group owns). */
    val hasFlashes: Boolean get() = layers.any { it.type == EffectLayerType.FLASHES }

    companion object {
        const val MIN_PALETTE = 2
        const val MAX_PALETTE = 8

        /** Layered effects per group: enough to stack, few enough to keep frames cheap. */
        const val MAX_LAYERS = 8
    }
}

internal fun EffectLayerEntity.toLayer(): EffectLayer = EffectLayer(id, type, brightnessShift, flashes, spin)

/** [layer] as a row of [sceneId]'s [slotId] at [position]; a negative (editor-only) id becomes a new row. */
internal fun layerEntity(sceneId: Long, slotId: Long, position: Int, layer: EffectLayer): EffectLayerEntity =
    EffectLayerEntity(
        id = if (layer.id > 0) layer.id else 0,
        sceneId = sceneId,
        slotId = slotId,
        position = position,
        type = layer.type,
        brightnessShift = layer.brightnessShift,
        flashes = layer.flashes,
        spin = layer.spin,
    )

/** Groups the scene's layer rows (sorted by slot, then position) by slot, in priority order. */
internal fun List<EffectLayerEntity>.layersBySlot(): Map<Long, List<EffectLayer>> {
    if (isEmpty()) return emptyMap()
    val result = HashMap<Long, MutableList<EffectLayer>>()
    for (row in this) result.getOrPut(row.slotId) { ArrayList(4) }.add(row.toLayer())
    return result
}

internal fun SceneLightEffectsEntity?.toGroupEffects(palette: List<FxColor>, layers: List<EffectLayer>): GroupEffects =
    GroupEffects(this?.colorShift ?: ColorShiftSettings(), palette, layers)

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
    layerRows: List<EffectLayerEntity> = emptyList(),
): Map<Long, Map<Long, GroupEffects>> {
    val rowCount = rows.size
    if (rowCount == 0 && layerRows.isEmpty()) return emptyMap()
    val palettes = HashMap<Long, HashMap<Long, MutableList<FxColor>>>()
    val colorCount = colors.size
    for (i in 0 until colorCount) {
        val c = colors[i]
        palettes.getOrPut(c.sceneId) { HashMap() }.getOrPut(c.slotId) { ArrayList(GroupEffects.MAX_PALETTE) }.add(c.color)
    }
    // Layers by scene, then slot (rows come sorted by scene, slot, position).
    val layers = HashMap<Long, HashMap<Long, MutableList<EffectLayer>>>()
    for (row in layerRows) layers.getOrPut(row.sceneId) { HashMap() }.getOrPut(row.slotId) { ArrayList(4) }.add(row.toLayer())
    val result = HashMap<Long, HashMap<Long, GroupEffects>>()
    for (i in 0 until rowCount) {
        val row = rows[i]
        val palette = palettes[row.sceneId]?.get(row.slotId).orEmpty()
        val own = layers[row.sceneId]?.remove(row.slotId).orEmpty()
        result.getOrPut(row.sceneId) { HashMap() }[row.slotId] = row.toGroupEffects(palette, own)
    }
    // Groups with layers but no Multi-color row.
    for ((sceneId, bySlot) in layers) {
        for ((slotId, list) in bySlot) result.getOrPut(sceneId) { HashMap() }[slotId] = GroupEffects(layers = list)
    }
    return result
}
