package com.dungeonvibes.light

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android 17 (API 37) blocks all local-network traffic, including our UDP to bulbs, for apps that
 * target it unless the user grants ACCESS_LOCAL_NETWORK at runtime. Earlier Android versions grant
 * it implicitly with INTERNET, so there's nothing to ask for there.
 */
@Singleton
class LocalNetworkPermission @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val isRequired: Boolean get() = Build.VERSION.SDK_INT >= API_ANDROID_17

    fun isGranted(): Boolean {
        if (!isRequired) return true
        return context.checkSelfPermission(NAME) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val NAME = "android.permission.ACCESS_LOCAL_NETWORK"
        private const val API_ANDROID_17 = 37
    }
}
