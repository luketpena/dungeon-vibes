package com.dungeonvibes.light

import com.dungeonvibes.data.db.BulbApplyTarget
import com.dungeonvibes.data.db.BulbEntity
import com.dungeonvibes.data.db.LightMode

/**
 * A light's color, protocol-neutral.
 * @param hue 0–360
 * @param saturation 0–1
 * @param brightness 0–1
 * @param kelvin non-null means white mode at this temperature; null means color mode.
 * @param colorKelvin color mode only: the white point mixed into a partly saturated color. Scenes
 *   use the default; effects vary it so a blend between a white and a color has no visible jump.
 */
data class LightState(
    val hue: Float,
    val saturation: Float,
    val brightness: Float,
    val kelvin: Int?,
    val colorKelvin: Int = COLOR_KELVIN,
) {
    companion object {
        /** White point sent with colors; the LIFX docs' examples use 3500. */
        const val COLOR_KELVIN = 3_500
    }
}

/** Where to reach one bulb. [serial] is the stable identity; [ip] can change between scans. */
data class BulbAddress(
    val protocolId: String,
    val serial: String,
    val ip: String,
)

/** A bulb that answered a discovery scan. [isOn] and [state] are null if it didn't report them. */
data class DiscoveredBulb(
    val address: BulbAddress,
    val label: String,
    val isOn: Boolean?,
    val state: LightState?,
)

class LightException(message: String, cause: Throwable? = null) : Exception(message, cause)

fun BulbEntity.toAddress(): BulbAddress = BulbAddress(protocolId, serial, lastKnownIp)

fun BulbApplyTarget.toAddress(): BulbAddress = BulbAddress(protocolId, serial, lastKnownIp)

/** The scene's settings for this bulb, or null when the bulb should be off. */
fun BulbApplyTarget.toLightStateOrNull(): LightState? {
    if (!isOn) return null
    return LightState(
        hue = hue ?: 0f,
        saturation = saturation ?: 0f,
        brightness = brightness ?: 0f,
        kelvin = if (mode == LightMode.WHITE) kelvin else null,
    )
}
