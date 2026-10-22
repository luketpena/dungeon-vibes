package com.dungeonvibes.light.fx

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android 13+ needs permission to show the "effects running" notification. It's asked for once,
 * the first time effects start; effects run either way (the notification just doesn't show).
 */
@Singleton
class NotificationPermission @Inject constructor(@ApplicationContext private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun shouldAsk(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (prefs.getBoolean(KEY_ASKED, false)) return false
        return ContextCompat.checkSelfPermission(context, NAME) != PackageManager.PERMISSION_GRANTED
    }

    fun markAsked() {
        prefs.edit().putBoolean(KEY_ASKED, true).apply()
    }

    companion object {
        const val NAME = Manifest.permission.POST_NOTIFICATIONS
        private const val PREFS_NAME = "permissions"
        private const val KEY_ASKED = "asked_notifications"
    }
}
