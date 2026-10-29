package com.dungeonvibes.light.fx

import com.dungeonvibes.data.db.BrightnessShiftSettings
import com.dungeonvibes.data.db.FxColor
import com.dungeonvibes.data.db.SpinSettings
import com.dungeonvibes.data.scene.EffectLayer
import com.dungeonvibes.data.scene.GroupEffects
import com.dungeonvibes.light.LightState
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Layered effects mixing (owner decisions, Oct 23, 2026): brightness layers multiply; higher layers win. */
class LayeredEffectsTest {

    private val base = LightState(hue = 30f, saturation = 1f, brightness = 0.8f, kelvin = null)

    private fun sample(effects: GroupEffects, t: Long = 100): Hsbk {
        val run = GroupEffectRun(Hsbk.of(base), effects, bulbCount = 1, Random(1))
        val out = Hsbk()
        run.flashKey(0, t)
        run.sample(0, t, out)
        return out
    }

    /** A steady level (its range is a single value). */
    private fun level(value: Float) = EffectLayer.brightness(BrightnessShiftSettings(levelMin = value, levelMax = value))

    /** A Spin that's lit the whole time on a one-bulb group, in [hue]. */
    private fun steadySpin(hue: Float, id: Long) =
        EffectLayer.spin(SpinSettings(color = FxColor.color(hue), level = 1f, attackMs = 0, holdMs = 10_000, releaseMs = 0, stepMs = 100), id)

    @Test
    fun oneBrightnessShift_setsTheLevel_twoMultiply() {
        assertEquals(0.5f, sample(GroupEffects(layers = listOf(level(0.5f)))).brightness, 0.001f)
        assertEquals(0.25f, sample(GroupEffects(layers = listOf(level(0.5f), level(0.5f)))).brightness, 0.001f)
    }

    @Test
    fun whereTwoLayersAreLit_theHigherOneWins() {
        val green = steadySpin(120f, id = 1)
        val blue = steadySpin(240f, id = 2)
        assertEquals(120f, sample(GroupEffects(layers = listOf(green, blue))).hue, 0.5f)
        assertEquals(240f, sample(GroupEffects(layers = listOf(blue, green))).hue, 0.5f)
    }

    @Test
    fun layersCompareBySettings_notId() {
        assertEquals(steadySpin(120f, id = 1), steadySpin(120f, id = -5))
        assertNotEquals(steadySpin(120f, id = 1), steadySpin(200f, id = 1))
    }
}
