package com.dungeonvibes.ui.common

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.dungeonvibes.data.db.FxColor
import com.dungeonvibes.data.db.LightDefaults
import com.dungeonvibes.data.db.LightMode
import com.dungeonvibes.data.db.SceneLightEntity
import kotlin.math.ln
import kotlin.math.pow

/** Display helpers that turn stored light settings into on-screen colors. */
object LightColors {
    const val MIN_KELVIN = 1_500
    const val MAX_KELVIN = 9_000

    /** Defaults for a slot that has no settings yet in a scene (REQUIREMENTS: white defaults to 2700 K). */
    const val DEFAULT_KELVIN = LightDefaults.KELVIN
    const val DEFAULT_HUE = LightDefaults.HUE
    const val DEFAULT_SATURATION = LightDefaults.SATURATION
    const val DEFAULT_BRIGHTNESS = LightDefaults.BRIGHTNESS

    /** Hue spectrum, built once. */
    val HueBrush: Brush = Brush.horizontalGradient(
        listOf(0f, 60f, 120f, 180f, 240f, 300f, 360f).map { Color.hsv(it, 1f, 1f) },
    )

    /** Warm to cool white, built once. */
    val KelvinBrush: Brush = Brush.horizontalGradient(
        (MIN_KELVIN..MAX_KELVIN step 1_500).map { kelvinToColor(it) },
    )

    fun saturationBrush(hue: Float): Brush =
        Brush.horizontalGradient(listOf(Color.hsv(hue, 0f, 1f), Color.hsv(hue, 1f, 1f)))

    fun brightnessBrush(top: Color): Brush = Brush.horizontalGradient(listOf(Color.Black, top))

    /**
     * Swatch color for a light. Brightness is compressed into 35–100 % so dim settings still read
     * as their hue on screen.
     */
    fun displayColor(light: SceneLightEntity): Color {
        val value = 0.35f + 0.65f * light.brightness.coerceIn(0f, 1f)
        if (light.mode == LightMode.WHITE) {
            val white = kelvinToColor(light.kelvin)
            return Color(white.red * value, white.green * value, white.blue * value)
        }
        return Color.hsv(light.hue.coerceIn(0f, 360f), light.saturation.coerceIn(0f, 1f), value)
    }

    /**
     * Swatch color for an effect palette or flash color, at its brightness (compressed like
     * [displayColor] for lights; flash and pulse colors are always 100 %).
     */
    fun displayColor(color: FxColor): Color {
        val value = 0.35f + 0.65f * color.brightness.coerceIn(0f, 1f)
        if (color.mode == LightMode.WHITE) {
            val white = kelvinToColor(color.kelvin)
            return Color(white.red * value, white.green * value, white.blue * value)
        }
        return Color.hsv(color.hue.coerceIn(0f, 360f), color.saturation.coerceIn(0f, 1f), value)
    }

    /** Full-brightness color of [light]'s hue or white, for the brightness slider's bright end. */
    fun fullBrightnessColor(light: SceneLightEntity): Color =
        if (light.mode == LightMode.WHITE) {
            kelvinToColor(light.kelvin)
        } else {
            Color.hsv(light.hue.coerceIn(0f, 360f), light.saturation.coerceIn(0f, 1f), 1f)
        }

    /** Approximate RGB of a black-body color temperature (Tanner Helland's curve fit). */
    fun kelvinToColor(kelvin: Int): Color {
        val t = kelvin.coerceIn(1_000, 40_000) / 100.0
        val red = if (t <= 66) 255.0 else 329.698727446 * (t - 60).pow(-0.1332047592)
        val green = if (t <= 66) {
            99.4708025861 * ln(t) - 161.1195681661
        } else {
            288.1221695283 * (t - 60).pow(-0.0755148492)
        }
        val blue = when {
            t >= 66 -> 255.0
            t <= 19 -> 0.0
            else -> 138.5177312231 * ln(t - 10) - 305.0447927307
        }
        return Color(
            red = (red.coerceIn(0.0, 255.0) / 255.0).toFloat(),
            green = (green.coerceIn(0.0, 255.0) / 255.0).toFloat(),
            blue = (blue.coerceIn(0.0, 255.0) / 255.0).toFloat(),
        )
    }
}
