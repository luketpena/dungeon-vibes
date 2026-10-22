package com.dungeonvibes

import android.app.Application
import com.dungeonvibes.light.fx.EffectServiceLauncher
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

// Hilt's root: generates the app-wide dependency container when the process starts.
@HiltAndroidApp
class DungeonVibesApplication : Application() {

    @Inject lateinit var effectService: EffectServiceLauncher

    override fun onCreate() {
        super.onCreate() // Hilt fills the @Inject fields here.
        effectService.start()
    }
}
