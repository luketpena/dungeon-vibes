package com.dungeonvibes.light

import kotlinx.coroutines.flow.Flow

/**
 * One bulb protocol (LIFX LAN today; Matter, Hue, etc. later). Only [LightController] talks to
 * adapters; UI and scene code never do.
 */
interface LightAdapter {
    /** Stored on each Bulb row, e.g. "lifx_lan". */
    val protocolId: String

    /** False hides this adapter from scans and fails its commands (the fake adapter's switch). */
    val isAvailable: Boolean get() = true

    /** Emits each bulb that answers within [timeoutMs], then completes. */
    fun discover(timeoutMs: Long): Flow<DiscoveredBulb>

    /** @param reliable true waits for an acknowledgement and retries once; false is fire-and-forget. */
    suspend fun setState(bulb: BulbAddress, state: LightState, fadeMs: Int, reliable: Boolean): Result<Unit>

    suspend fun setPower(bulb: BulbAddress, on: Boolean, fadeMs: Int, reliable: Boolean): Result<Unit>

    /** Briefly blinks the bulb so the user can find it, leaving it as it was. */
    suspend fun identify(bulb: BulbAddress)
}
