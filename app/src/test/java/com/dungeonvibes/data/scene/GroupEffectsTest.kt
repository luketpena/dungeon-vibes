package com.dungeonvibes.data.scene

import com.dungeonvibes.data.db.BrightnessShiftSettings
import com.dungeonvibes.data.db.ColorShiftSettings
import com.dungeonvibes.data.db.FlashSettings
import com.dungeonvibes.data.db.FxColor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupEffectsTest {

    private val multiColor = GroupEffects(
        colorShift = ColorShiftSettings(enabled = true),
        palette = listOf(FxColor.color(0f), FxColor.color(120f)),
    )

    @Test
    fun multiColorAlone_animatesButIsNotAnEffect() {
        assertTrue(multiColor.hasAny)
        assertFalse(multiColor.hasEffectLayers)
    }

    @Test
    fun brightnessShiftOrFlashes_areEffects() {
        assertTrue(multiColor.withBrightnessShift(BrightnessShiftSettings(enabled = true)).hasEffectLayers)
        assertTrue(GroupEffects(layers = listOf(EffectLayer.flashes(FlashSettings(enabled = true)))).hasEffectLayers)
    }
}
