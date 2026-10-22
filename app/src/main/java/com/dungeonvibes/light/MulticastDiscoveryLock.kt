package com.dungeonvibes.light

import android.content.Context
import android.net.wifi.WifiManager
import com.dungeonvibes.data.bulb.DiscoveryLock
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MulticastDiscoveryLock @Inject constructor(
    @ApplicationContext context: Context,
) : DiscoveryLock {

    private val lock: WifiManager.MulticastLock? =
        context.getSystemService(WifiManager::class.java)
            ?.createMulticastLock("dungeon-vibes-discovery")
            ?.apply { setReferenceCounted(false) }

    override fun acquire() {
        lock?.acquire()
    }

    override fun release() {
        val held = lock ?: return
        if (held.isHeld) held.release()
    }
}
