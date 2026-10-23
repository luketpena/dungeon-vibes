package com.dungeonvibes.data.scene

import com.dungeonvibes.data.db.BulbApplyTarget
import com.dungeonvibes.data.db.BulbEntity
import com.dungeonvibes.data.db.LightDefaults
import com.dungeonvibes.data.db.LightMode
import com.dungeonvibes.data.db.LightSlotEntity
import com.dungeonvibes.data.db.SceneLightEntity
import com.dungeonvibes.data.db.SlotBulbEntity

/**
 * The bulbs and groups a scene is worked out against: every bulb (in name order), the user's
 * groups in their order (higher wins a shared bulb), which bulbs are in which groups (each group's
 * in its bulb order), and each bulb's individual-light slot, if it has one yet.
 */
class SceneSetup(
    val bulbs: List<BulbEntity>,
    val groups: List<LightSlotEntity>,
    val assignments: List<SlotBulbEntity>,
    /** bulb id → its individual-light slot id. */
    val bulbSlots: Map<Long, Long>,
) {
    /** bulb id → its groups, in group order. */
    val groupsOfBulb: Map<Long, List<Long>> by lazy {
        val rank = HashMap<Long, Int>(groups.size * 2)
        val groupCount = groups.size
        for (i in 0 until groupCount) rank[groups[i].id] = i
        val result = HashMap<Long, MutableList<Long>>()
        for (assignment in assignments) {
            // A stray assignment to a hidden slot (there shouldn't be any) is ignored.
            if (assignment.slotId !in rank) continue
            result.getOrPut(assignment.bulbId) { ArrayList(2) }.add(assignment.slotId)
        }
        for (list in result.values) list.sortBy { rank.getValue(it) }
        result
    }

    /** bulb id → (group id → its position in that group), the order Wave and Spin follow. */
    val positionsOfBulb: Map<Long, Map<Long, Int>> by lazy {
        val result = HashMap<Long, HashMap<Long, Int>>()
        for (assignment in assignments) {
            result.getOrPut(assignment.bulbId) { HashMap(2) }[assignment.slotId] = assignment.position
        }
        result
    }

    /**
     * Every bulb in room order, the order the whole room's effects follow (All lights, FX on
     * every light): the groups in their order, each group's bulbs in its bulb order (a bulb in
     * several groups where it first appears), then any other bulbs by name.
     */
    val roomOrder: List<BulbEntity> by lazy {
        val byId = HashMap<Long, BulbEntity>(bulbs.size * 2)
        for (bulb in bulbs) byId[bulb.id] = bulb
        val inGroup = HashMap<Long, MutableList<SlotBulbEntity>>()
        for (assignment in assignments) inGroup.getOrPut(assignment.slotId) { ArrayList(4) }.add(assignment)
        val result = ArrayList<BulbEntity>(bulbs.size)
        val placed = HashSet<Long>(bulbs.size * 2)
        for (group in groups) {
            val members = inGroup[group.id] ?: continue
            members.sortWith(compareBy<SlotBulbEntity>({ it.position }, { it.bulbId }))
            for (member in members) {
                val bulb = byId[member.bulbId] ?: continue
                if (placed.add(bulb.id)) result.add(bulb)
            }
        }
        for (bulb in bulbs) if (placed.add(bulb.id)) result.add(bulb)
        result
    }

    companion object {
        val EMPTY = SceneSetup(emptyList(), emptyList(), emptyList(), emptyMap())
    }
}

/**
 * What every bulb shows in a scene, given its light rows ([lights], slot id → row) and its All
 * lights switch. For each bulb, first match wins:
 *
 * 1. Its individual light, if the scene has one (on or off: an individual light set off is a
 *    deliberate dark bulb).
 * 2. With All lights on, the All-lights settings ([roomSlotId]'s row, or the defaults).
 * 3. The highest group in the list that holds it and is on in the scene. Groups that are off
 *    don't count (owner decision), so they never darken a bulb another group lights.
 * 4. Otherwise it's off.
 *
 * Bulbs in no group are left alone (rule 8) unless a scene has ever set them individually (they
 * then go off in scenes that don't light them, like any other bulb). Flashes from
 * every other lit slot holding the bulb still reach it ([BulbApplyTarget.flashSlotIds]); with All
 * lights on that's the All-lights slot under an individual light, since All lights replaces the
 * groups. The result is in room order ([SceneSetup.roomOrder]).
 */
fun resolveScene(
    setup: SceneSetup,
    lights: Map<Long, SceneLightEntity>,
    allLightsOn: Boolean,
    roomSlotId: Long?,
): List<BulbApplyTarget> {
    val bulbs = setup.roomOrder
    val count = bulbs.size
    val positionsOfBulb = setup.positionsOfBulb
    val result = ArrayList<BulbApplyTarget>(count)
    val groupsOfBulb = setup.groupsOfBulb
    val room = if (allLightsOn) roomSlotId else null
    val roomLight = room?.let { lights[it] }
    for (i in 0 until count) {
        val bulb = bulbs[i]
        val groups = groupsOfBulb[bulb.id].orEmpty()
        val bulbSlot = setup.bulbSlots[bulb.id]
        val single = bulbSlot?.let { lights[it] }
        // Bulbs in no group are left alone, unless some scene has set them individually: then
        // scenes that don't light them turn them off, like any other bulb.
        if (groups.isEmpty() && bulbSlot == null) continue

        // The lit slots holding the bulb, best first: the All-lights slot, or its lit groups.
        val lit: List<Long> = when {
            room != null -> listOf(room)
            allLightsOn -> emptyList()
            else -> groups.filter { lights[it]?.isOn == true }
        }

        val slotId: Long
        val row: SceneLightEntity?
        val on: Boolean
        when {
            single != null -> {
                slotId = bulbSlot!!
                row = single
                on = single.isOn
            }
            allLightsOn -> {
                // The All-lights row is lit whatever its own switch says; no row means the defaults.
                slotId = room ?: groups.firstOrNull() ?: bulbSlot!!
                row = roomLight
                on = true
            }
            lit.isNotEmpty() -> {
                slotId = lit[0]
                row = lights.getValue(slotId)
                on = true
            }
            else -> {
                slotId = groups.firstOrNull() ?: bulbSlot!!
                row = lights[slotId]
                on = false
            }
        }
        val flashes = if (lit.isEmpty() || (lit.size == 1 && lit[0] == slotId)) {
            emptyList()
        } else {
            lit.filter { it != slotId }
        }
        result.add(
            BulbApplyTarget(
                bulbId = bulb.id,
                protocolId = bulb.protocolId,
                serial = bulb.serial,
                lastKnownIp = bulb.lastKnownIp,
                slotId = slotId,
                isOn = on,
                mode = row?.mode ?: LightMode.WHITE,
                hue = row?.hue ?: LightDefaults.HUE,
                saturation = row?.saturation ?: LightDefaults.SATURATION,
                kelvin = row?.kelvin ?: LightDefaults.KELVIN,
                brightness = row?.brightness ?: LightDefaults.BRIGHTNESS,
                bulbName = bulb.name,
                positions = positionsOfBulb[bulb.id].orEmpty(),
                groupIds = groups,
                flashSlotIds = if (on) flashes else emptyList(),
            ),
        )
    }
    return result
}

/**
 * A Multi-color slot fades in to its first palette color (at that color's brightness) rather
 * than its (hidden) single color, so its effects pick up from there without a jump.
 */
fun List<BulbApplyTarget>.withRestingColors(effectsOf: (Long) -> GroupEffects?): List<BulbApplyTarget> {
    val count = size
    val result = ArrayList<BulbApplyTarget>(count)
    for (i in 0 until count) {
        val target = this[i]
        val fx = if (target.isOn) effectsOf(target.slotId) else null
        if (fx == null || !fx.runsColorShift) {
            result.add(target)
            continue
        }
        val first = fx.palette[0]
        result.add(
            target.copy(mode = first.mode, hue = first.hue, saturation = first.saturation, kelvin = first.kelvin, brightness = first.brightness),
        )
    }
    return result
}
