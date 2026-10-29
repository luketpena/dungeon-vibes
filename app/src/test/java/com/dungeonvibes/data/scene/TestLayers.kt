package com.dungeonvibes.data.scene

import com.dungeonvibes.data.db.BrightnessShiftSettings
import com.dungeonvibes.data.db.EffectLayerType
import com.dungeonvibes.data.db.FlashSettings
import com.dungeonvibes.data.db.SpinSettings

/* Test shorthands for groups with one layer of each kind (as before layered effects). */

val GroupEffects.flashes: FlashSettings get() = layers.first { it.type == EffectLayerType.FLASHES }.flashes

val GroupEffects.brightnessShift: BrightnessShiftSettings
    get() = layers.first { it.type == EffectLayerType.BRIGHTNESS_SHIFT }.brightnessShift

val GroupEffects.spin: SpinSettings get() = layers.first { it.type == EffectLayerType.SPIN }.spin

/** These effects with their Flashes layer replaced by [settings] (or added on top). */
fun GroupEffects.withFlashes(settings: FlashSettings): GroupEffects = withLayer(EffectLayer.flashes(settings))

fun GroupEffects.withBrightnessShift(settings: BrightnessShiftSettings): GroupEffects = withLayer(EffectLayer.brightness(settings))

fun GroupEffects.withSpin(settings: SpinSettings): GroupEffects = withLayer(EffectLayer.spin(settings))

private fun GroupEffects.withLayer(layer: EffectLayer): GroupEffects {
    val index = layers.indexOfFirst { it.type == layer.type }
    if (index < 0) return copy(layers = listOf(layer) + layers)
    return copy(layers = layers.toMutableList().also { it[index] = layer.copy(id = layers[index].id) })
}
