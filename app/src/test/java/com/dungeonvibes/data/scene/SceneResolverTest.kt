package com.dungeonvibes.data.scene

import com.dungeonvibes.data.db.BulbEntity
import com.dungeonvibes.data.db.FlashSettings
import com.dungeonvibes.data.db.LightMode
import com.dungeonvibes.data.db.LightSlotEntity
import com.dungeonvibes.data.db.SceneLightEntity
import com.dungeonvibes.data.db.SlotBulbEntity
import com.dungeonvibes.light.fx.effectGroupsFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Who decides each bulb's look (owner rules, Oct 22, 2026): individual light > All lights > highest lit group. */
class SceneResolverTest {

    private val surround = LightSlotEntity(id = 1, name = "Surround", sortOrder = 0)
    private val corner = LightSlotEntity(id = 2, name = "Corner", sortOrder = 1)
    private val room = 9L
    private val cornerSingle = 20L

    private val a = BulbEntity(id = 1, protocolId = "fake", serial = "a", lastKnownIp = "0.0.0.1", name = "A", lastSeenAt = 0)
    private val b = BulbEntity(id = 2, protocolId = "fake", serial = "b", lastKnownIp = "0.0.0.2", name = "B", lastSeenAt = 0)
    private val loose = BulbEntity(id = 3, protocolId = "fake", serial = "c", lastKnownIp = "0.0.0.3", name = "C", lastSeenAt = 0)

    /** A and B are both in Surround; B is also in Corner. */
    private fun setup(groups: List<LightSlotEntity> = listOf(surround, corner), singles: Map<Long, Long> = emptyMap()) = SceneSetup(
        bulbs = listOf(a, b, loose),
        groups = groups,
        assignments = listOf(SlotBulbEntity(1, 1), SlotBulbEntity(1, 2), SlotBulbEntity(2, 2)),
        bulbSlots = singles,
    )

    private fun light(slotId: Long, on: Boolean, hue: Float) =
        SceneLightEntity(sceneId = 1, slotId = slotId, isOn = on, mode = LightMode.COLOR, hue = hue, saturation = 1f, kelvin = 2_700, brightness = 0.5f)

    private fun lights(vararg rows: SceneLightEntity) = rows.associateBy { it.slotId }

    @Test
    fun aSharedBulb_goesToTheHigherLitGroup_andTheLowerOneOnlyFlashesIt() {
        val targets = resolveScene(setup(), lights(light(1, true, 0f), light(2, true, 120f)), allLightsOn = false, roomSlotId = room)
            .associateBy { it.bulbId }

        assertEquals(1L, targets.getValue(2).slotId)
        assertEquals(0f, targets.getValue(2).hue)
        assertEquals(listOf(2L), targets.getValue(2).flashSlotIds)
        assertEquals(listOf(1L, 2L), targets.getValue(2).groupIds)
        assertTrue(targets.getValue(1).flashSlotIds.isEmpty())
        // Bulbs in no group are left alone.
        assertFalse(3L in targets)
    }

    @Test
    fun reorderingGroups_changesWhoWins() {
        val targets = resolveScene(setup(groups = listOf(corner, surround)), lights(light(1, true, 0f), light(2, true, 120f)), false, room)
            .associateBy { it.bulbId }

        assertEquals(2L, targets.getValue(2).slotId)
        assertEquals(120f, targets.getValue(2).hue)
        assertEquals(listOf(1L), targets.getValue(2).flashSlotIds)
    }

    @Test
    fun anOffGroup_doesntCount() {
        val targets = resolveScene(setup(), lights(light(1, false, 0f), light(2, true, 120f)), false, room)
            .associateBy { it.bulbId }

        assertEquals(2L, targets.getValue(2).slotId)
        assertTrue(targets.getValue(2).isOn)
        assertTrue(targets.getValue(2).flashSlotIds.isEmpty())
        assertFalse(targets.getValue(1).isOn)
    }

    @Test
    fun anIndividualLight_winsOverGroupsAndAllLights_andOffMeansDark() {
        val singles = mapOf(2L to cornerSingle)
        val moon = SceneLightEntity(1, cornerSingle, true, LightMode.WHITE, 0f, 0f, 6_500, 1f)
        val withGroups = resolveScene(setup(singles = singles), lights(light(1, true, 0f), moon), false, room)
            .associateBy { it.bulbId }
        assertEquals(cornerSingle, withGroups.getValue(2).slotId)
        assertEquals(6_500, withGroups.getValue(2).kelvin)
        // The lit group's flashes still reach it.
        assertEquals(listOf(1L), withGroups.getValue(2).flashSlotIds)

        val underAllLights = resolveScene(setup(singles = singles), lights(light(room, true, 240f), moon), true, room)
            .associateBy { it.bulbId }
        assertEquals(cornerSingle, underAllLights.getValue(2).slotId)
        assertEquals(room, underAllLights.getValue(1).slotId)
        assertEquals(240f, underAllLights.getValue(1).hue)

        val dark = resolveScene(setup(singles = singles), lights(light(1, true, 0f), moon.copy(isOn = false)), false, room)
            .associateBy { it.bulbId }
        assertFalse(dark.getValue(2).isOn)
        assertTrue(dark.getValue(2).flashSlotIds.isEmpty())
    }

    @Test
    fun aBulbInNoGroup_setIndividually_isLit_andGoesOffInOtherScenes() {
        val singles = mapOf(3L to 30L)
        val lit = resolveScene(setup(singles = singles), lights(light(30, true, 60f)), false, room).associateBy { it.bulbId }
        assertTrue(lit.getValue(3).isOn)

        val other = resolveScene(setup(singles = singles), lights(light(1, true, 0f)), false, room).associateBy { it.bulbId }
        assertFalse(other.getValue(3).isOn)
    }

    @Test
    fun effectGroups_ownerTakesTheLook_otherLitGroupsOnlyFlash() {
        val targets = resolveScene(setup(), lights(light(1, true, 0f), light(2, true, 120f)), false, room)
        val storm = GroupEffects(layers = listOf(EffectLayer.flashes(FlashSettings(enabled = true))))
        val groups = effectGroupsFor(targets) { slotId -> if (slotId == 2L) storm else null }.associateBy { it.slotId }

        // Corner flashes B, which Surround owns: Surround runs too (no effects of its own) to carry B's look.
        val cornerGroup = groups.getValue(2)
        assertEquals(1, cornerGroup.bulbs.size)
        assertEquals(cornerGroup.bulbs.toSet(), cornerGroup.flashOnly)
        val surroundGroup = groups.getValue(1)
        assertTrue(surroundGroup.flashOnly.isEmpty())
        assertEquals(listOf("b"), surroundGroup.bulbs.map { it.serial })
        assertEquals(0f, surroundGroup.base.hue)
    }

    @Test
    fun targets_comeInRoomOrder_groupsInOrderAndEachGroupsBulbsByPosition() {
        // Surround holds B then A (dragged that way); C is in no group but set individually.
        val setup = SceneSetup(
            bulbs = listOf(a, b, loose),
            groups = listOf(surround),
            assignments = listOf(SlotBulbEntity(1, 2, position = 0), SlotBulbEntity(1, 1, position = 1)),
            bulbSlots = mapOf(3L to 30L),
        )
        val targets = resolveScene(setup, lights(light(1, true, 0f), light(30, true, 60f)), allLightsOn = false, roomSlotId = room)

        assertEquals(listOf(2L, 1L, 3L), targets.map { it.bulbId })
        assertEquals(mapOf(1L to 0), targets[0].positions)
        assertEquals(mapOf(1L to 1), targets[1].positions)
    }
}
