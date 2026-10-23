package com.dungeonvibes.light

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the app has heard from each bulb lately: which answered the last scan, and which answered
 * or missed the scenes applied since. A bulb is unreachable when it missed the last scene sent to
 * it, or missed the last scan and hasn't answered a scene since.
 */
data class Reachability(
    val scanned: Boolean = false,
    val found: Set<Long> = emptySet(),
    val confirmed: Set<Long> = emptySet(),
    val failed: Set<Long> = emptySet(),
) {
    fun isUnreachable(bulbId: Long): Boolean =
        bulbId in failed || (scanned && bulbId !in found && bulbId !in confirmed)
}

/** Tracks [Reachability] for the lights menu's warning (fed by discovery and scene applies). */
@Singleton
class BulbReachability @Inject constructor() {
    private val _state = MutableStateFlow(Reachability())
    val state: StateFlow<Reachability> = _state.asStateFlow()

    /** A scan finished: [found] answered. Their old misses are forgotten. */
    fun onScan(found: Set<Long>) {
        _state.update { Reachability(scanned = true, found = found, confirmed = it.confirmed - found, failed = it.failed - found) }
    }

    /** A scene went to [targets]; [failed] didn't confirm it. */
    fun onApply(targets: Collection<Long>, failed: Collection<Long>) {
        if (targets.isEmpty()) return
        val failedSet = failed.toHashSet()
        _state.update { current ->
            val confirmed = HashSet(current.confirmed)
            val stillFailed = HashSet(current.failed)
            for (id in targets) {
                if (id in failedSet) {
                    confirmed.remove(id)
                    stillFailed.add(id)
                } else {
                    confirmed.add(id)
                    stillFailed.remove(id)
                }
            }
            current.copy(confirmed = confirmed, failed = stillFailed)
        }
    }
}
