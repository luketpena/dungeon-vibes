package com.dungeonvibes.light

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the app last told one bulb: [color] (null means off), reached over a [fadeMs] fade that
 * starts when this is published. Not compared by value, so every command is published, even a
 * repeat with a different fade.
 */
class CommandedLight(val color: LightState?, val fadeMs: Int)

/**
 * The colors the app is sending, per bulb, so the screen can move with the room (the active tile's
 * glow). It mirrors our own commands rather than asking the bulbs, which would be slow and costs
 * packets: [LightController] records every command it sends (scene applies, effect frames, preview,
 * on and off). A bulb that missed a packet still shows here as commanded.
 */
@Singleton
class LightMirror @Inject constructor() {
    private val bulbs = ConcurrentHashMap<String, MutableStateFlow<CommandedLight?>>()

    /** The last command for the bulb with this [keyOf] key; null until the app has sent it one. */
    fun observe(key: String): StateFlow<CommandedLight?> = flowFor(key)

    /** Records a command for the bulb [key] ([keyOf]). [color] null means it was turned off. */
    fun record(key: String, color: LightState?, fadeMs: Int) {
        flowFor(key).value = CommandedLight(color, fadeMs)
    }

    private fun flowFor(key: String): MutableStateFlow<CommandedLight?> =
        bulbs[key] ?: bulbs.computeIfAbsent(key) { MutableStateFlow(null) }

    companion object {
        /** A bulb's identity across IP changes; the same key [LightController] uses. */
        fun keyOf(protocolId: String, serial: String): String = protocolId + '/' + serial
    }
}
