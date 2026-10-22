package com.dungeonvibes.data.bulb

import com.dungeonvibes.light.LightController
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

/** Holds a Wi-Fi multicast lock while scanning; some phones drop broadcast replies without it. */
interface DiscoveryLock {
    fun acquire()
    fun release()
}

/**
 * Scans for bulbs, records them (new bulbs are added, known serials get their new IP), and tracks
 * which bulbs answered the latest scan.
 */
@Singleton
class BulbDiscovery @Inject constructor(
    private val lightController: LightController,
    private val bulbs: BulbRepository,
    private val lock: DiscoveryLock,
) {
    private val scanMutex = Mutex()

    private val _onlineBulbIds = MutableStateFlow<Set<Long>>(emptySet())

    /** Ids of bulbs that answered the most recent scan. */
    val onlineBulbIds: StateFlow<Set<Long>> = _onlineBulbIds.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    /** Runs one scan. If a scan is already running, returns at once instead of starting another. */
    suspend fun scan(timeoutMs: Long = DEFAULT_SCAN_MS) {
        if (!scanMutex.tryLock()) return
        try {
            _isScanning.value = true
            lock.acquire()
            val found = HashSet<Long>()
            lightController.discover(timeoutMs).collect { bulb ->
                val address = bulb.address
                val id = bulbs.recordSeen(address.protocolId, address.serial, address.ip, bulb.label)
                // Show bulbs as online as soon as they answer, not only when the scan ends.
                if (found.add(id)) _onlineBulbIds.value = _onlineBulbIds.value + id
            }
            _onlineBulbIds.value = found
        } finally {
            lock.release()
            _isScanning.value = false
            scanMutex.unlock()
        }
    }

    companion object {
        const val DEFAULT_SCAN_MS = 2_000L
    }
}
