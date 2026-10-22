package com.dungeonvibes.data.debug

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Developer switches, kept in SharedPreferences (the platform key-value store, like localStorage). */
@Singleton
class DebugSettings @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _useFakeBulbs = MutableStateFlow(prefs.getBoolean(KEY_USE_FAKE_BULBS, false))

    /** When true, scans also find the simulated bulbs and commands to them succeed. */
    val useFakeBulbs: StateFlow<Boolean> = _useFakeBulbs.asStateFlow()

    fun setUseFakeBulbs(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_USE_FAKE_BULBS, enabled).apply()
        _useFakeBulbs.value = enabled
    }

    private companion object {
        const val PREFS_NAME = "debug_settings"
        const val KEY_USE_FAKE_BULBS = "use_fake_bulbs"
    }
}
