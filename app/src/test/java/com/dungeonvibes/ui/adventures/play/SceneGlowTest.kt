package com.dungeonvibes.ui.adventures.play

import androidx.compose.ui.graphics.Color
import com.dungeonvibes.ui.scenes.SceneSwatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneGlowTest {

    private val bulbs = GlowBulbs(
        bySlot = mapOf(1L to listOf("a", "b"), 2L to listOf("c")),
        all = listOf("a", "b", "c"),
    )

    @Test
    fun eachBulbOfEachLitGroup_getsAnArc_inSwatchOrder() {
        val sources = glowSources(listOf(SceneSwatch(2L, Color.Red), SceneSwatch(1L, Color.Blue)), allLightsOn = false, bulbs)

        assertEquals(listOf("c", "a", "b"), sources.map { it.bulbKey })
        assertEquals(listOf(Color.Red, Color.Blue, Color.Blue), sources.map { it.fallback })
    }

    @Test
    fun aLitGroupWithoutBulbs_showsItsSwatchColor() {
        val sources = glowSources(listOf(SceneSwatch(9L, Color.Green)), allLightsOn = false, bulbs)

        assertEquals(listOf(GlowSource(null, Color.Green)), sources)
    }

    @Test
    fun allLights_coversEveryBulb() {
        val sources = glowSources(listOf(SceneSwatch(99L, Color.White)), allLightsOn = true, bulbs)

        assertEquals(listOf("a", "b", "c"), sources.map { it.bulbKey })
    }

    @Test
    fun aSceneWithEverythingOff_hasNoGlow() {
        assertTrue(glowSources(emptyList(), allLightsOn = false, bulbs).isEmpty())
    }

    @Test
    fun darkBulbs_addNothing_andLitOnesStartAtAThirdStrength() {
        assertEquals(0f, glowLevelOf(0f), 0f)
        assertEquals(0.35f + 0.65f * 0.01f, glowLevelOf(0.01f), 0.0001f)
        assertEquals(1f, glowLevelOf(1f), 0.0001f)
    }
}
