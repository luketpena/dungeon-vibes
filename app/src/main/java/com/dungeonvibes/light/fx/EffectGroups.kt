package com.dungeonvibes.light.fx

import com.dungeonvibes.data.db.BulbApplyTarget
import com.dungeonvibes.data.scene.GroupEffects
import com.dungeonvibes.light.BulbAddress
import com.dungeonvibes.light.LightState
import com.dungeonvibes.light.toAddress
import com.dungeonvibes.light.toLightStateOrNull

/**
 * The effect groups that play a worked-out scene ([targets], in room order, from
 * SceneResolver; resting colors already applied). Each lit bulb is owned by its winning slot, which
 * gives it its look and its Color and Brightness shifts; every other lit slot holding it adds only
 * its Flashes (owner decision). A winning slot without effects is still included when another
 * slot flashes one of its bulbs, so the runner knows that bulb's look. Dark bulbs take no effects.
 * Each group lists its bulbs in its own bulb order (Setup → Groups), the order Wave and Spin
 * follow; the All-lights and individual slots keep room order.
 */
fun effectGroupsFor(targets: List<BulbApplyTarget>, effectsOf: (Long) -> GroupEffects?): List<EffectGroup> {
    val builders = LinkedHashMap<Long, GroupBuilder>()
    val count = targets.size
    for (i in 0 until count) {
        val target = targets[i]
        val state = target.toLightStateOrNull() ?: continue
        val address = target.toAddress()
        var flashed = false
        val sources = target.flashSlotIds
        val sourceCount = sources.size
        for (s in 0 until sourceCount) {
            val slotId = sources[s]
            val fx = effectsOf(slotId) ?: continue
            if (!fx.flashes.enabled) continue
            val builder = builders.getOrPut(slotId) { GroupBuilder(slotId, fx) }
            builder.add(address, state, owned = false, order = target.positions[slotId] ?: (ROOM_RANK + i))
            flashed = true
        }
        val own = effectsOf(target.slotId)
        if ((own != null && own.hasAny) || flashed) {
            builders.getOrPut(target.slotId) { GroupBuilder(target.slotId, own ?: GroupEffects()) }
                .add(address, state, owned = true, order = target.positions[target.slotId] ?: (ROOM_RANK + i))
        }
    }
    if (builders.isEmpty()) return emptyList()
    val result = ArrayList<EffectGroup>(builders.size)
    for (builder in builders.values) result.add(builder.build())
    return result
}

/** Bulbs a group doesn't list (All lights, individual lights) sort after any it does, in room order. */
private const val ROOM_RANK = 1_000_000

private class GroupBuilder(val slotId: Long, val effects: GroupEffects) {
    private val bulbs = ArrayList<BulbAddress>(4)
    private val orders = ArrayList<Int>(4)
    private var flashOnly: HashSet<BulbAddress>? = null
    private var base: LightState? = null
    private var anyState: LightState? = null

    fun add(address: BulbAddress, state: LightState, owned: Boolean, order: Int) {
        bulbs.add(address)
        orders.add(order)
        if (anyState == null) anyState = state
        if (owned) {
            // Every bulb a slot owns shares its settings, so the first one is the slot's base.
            if (base == null) base = state
        } else {
            (flashOnly ?: HashSet<BulbAddress>().also { flashOnly = it }).add(address)
        }
    }

    /** A slot that only flashes bulbs never shows its base; any of its bulbs' looks stands in. */
    fun build(): EffectGroup {
        val count = bulbs.size
        var sorted = true
        for (i in 1 until count) if (orders[i] < orders[i - 1]) sorted = false
        val ordered = if (sorted) bulbs else (0 until count).sortedBy { orders[it] }.map { bulbs[it] }
        return EffectGroup(slotId, base ?: anyState!!, effects, ordered, flashOnly ?: emptySet())
    }
}
