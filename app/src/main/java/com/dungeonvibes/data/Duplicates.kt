package com.dungeonvibes.data

/** The name a duplicate gets (duplicate names are allowed, REQUIREMENTS → Edge cases). */
fun copyName(name: String): String = "$name copy"

/** [ordered] with [newId] moved to just after [afterId] (or left at the end if that's gone). */
fun placedAfter(ordered: List<Long>, afterId: Long, newId: Long): List<Long> {
    val count = ordered.size
    val result = ArrayList<Long>(count)
    var placed = false
    for (i in 0 until count) {
        val id = ordered[i]
        if (id == newId) continue
        result.add(id)
        if (id == afterId) {
            result.add(newId)
            placed = true
        }
    }
    if (!placed) result.add(newId)
    return result
}
