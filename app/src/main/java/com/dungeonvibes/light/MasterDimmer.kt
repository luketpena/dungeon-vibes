package com.dungeonvibes.light

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The master dimmer (lights menu): one level, 5–100 %, that scales the brightness of everything
 * the app sends (scenes, effects, FX, preview), so the table can dim the room for reading
 * without touching any scene. [LightController] applies it.
 */
@Singleton
class MasterDimmer @Inject constructor() {
    private val _level = MutableStateFlow(FULL)
    val level: StateFlow<Float> = _level.asStateFlow()

    /** Sets the level (clamped to [MIN]–[FULL]); returns false if it didn't change. */
    fun set(level: Float): Boolean {
        val clamped = level.coerceIn(MIN, FULL)
        if (clamped == _level.value) return false
        _level.value = clamped
        return true
    }

    companion object {
        const val FULL = 1f

        /** Never all the way down: dark is what Turn off lights is for. */
        const val MIN = 0.05f
    }
}

/** Remembers the dimmer level between launches (SharedPreferences; no extra dependency). */
@Singleton
class DimmerStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): Float = prefs.getFloat(KEY_LEVEL, MasterDimmer.FULL)

    fun save(level: Float) {
        prefs.edit().putFloat(KEY_LEVEL, level).apply()
    }

    private companion object {
        const val PREFS = "lights"
        const val KEY_LEVEL = "master_dimmer"
    }
}
