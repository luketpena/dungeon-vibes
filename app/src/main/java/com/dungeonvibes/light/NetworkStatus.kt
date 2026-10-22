package com.dungeonvibes.light

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn

/** Whether the phone is on a local network (Wi-Fi or Ethernet) where bulbs could be reached. */
interface NetworkStatus {
    val isOnLocalNetwork: StateFlow<Boolean>
}

@Singleton
class AndroidNetworkStatus @Inject constructor(
    @ApplicationContext context: Context,
    @ApplicationScope scope: CoroutineScope,
) : NetworkStatus {

    private val connectivity: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)

    // callbackFlow turns Android's listener-style API into a Flow; stateIn keeps the latest value
    // and unregisters the listener 5 s after nobody is watching.
    override val isOnLocalNetwork: StateFlow<Boolean> = callbackFlow {
        val manager = connectivity
        if (manager == null) {
            trySend(false)
            awaitClose()
            return@callbackFlow
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(isLocal(capabilities))
            }

            override fun onLost(network: Network) {
                trySend(false)
            }
        }
        trySend(currentlyLocal())
        manager.registerDefaultNetworkCallback(callback)
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), currentlyLocal())

    private fun currentlyLocal(): Boolean {
        val manager = connectivity ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return isLocal(capabilities)
    }

    private fun isLocal(capabilities: NetworkCapabilities): Boolean =
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
}
