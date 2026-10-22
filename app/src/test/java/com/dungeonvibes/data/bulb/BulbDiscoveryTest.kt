package com.dungeonvibes.data.bulb

import com.dungeonvibes.data.DatabaseTest
import com.dungeonvibes.light.LightController
import com.dungeonvibes.light.fake.FakeLightAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class BulbDiscoveryTest : DatabaseTest() {

    private lateinit var fake: FakeLightAdapter
    private lateinit var bulbs: BulbRepository
    private lateinit var discovery: BulbDiscovery
    private var locksHeld = 0
    private var maxLocksHeld = 0

    private val lock = object : DiscoveryLock {
        override fun acquire() {
            locksHeld++
            maxLocksHeld = maxOf(maxLocksHeld, locksHeld)
        }

        override fun release() {
            locksHeld--
        }
    }

    @Before
    fun setUp() {
        fake = FakeLightAdapter()
        bulbs = BulbRepository(db, time)
        discovery = BulbDiscovery(LightController(setOf(fake), CoroutineScope(Dispatchers.Unconfined)), bulbs, lock)
    }

    @Test
    fun scan_recordsBulbsWithTheirLabels_andMarksThemOnline(): Unit = runBlocking {
        discovery.scan(timeoutMs = 100)

        val recorded = bulbs.observeBulbs().first()
        assertEquals(listOf("Fake bulb 1", "Fake bulb 2", "Fake bulb 3"), recorded.map { it.name })
        assertEquals(recorded.map { it.id }.toSet(), discovery.onlineBulbIds.value)
    }

    @Test
    fun scan_bulbThatStopsAnswering_staysKnownButGoesOffline(): Unit = runBlocking {
        discovery.scan(timeoutMs = 100)
        fake.offlineSerials += FakeLightAdapter.serialFor(2)

        discovery.scan(timeoutMs = 100)

        val recorded = bulbs.observeBulbs().first()
        assertEquals(3, recorded.size)
        val offline = recorded.single { it.serial == FakeLightAdapter.serialFor(2) }
        assertFalse(offline.id in discovery.onlineBulbIds.value)
        assertEquals(2, discovery.onlineBulbIds.value.size)
    }

    @Test
    fun scan_keepsUserRenames() : Unit = runBlocking {
        discovery.scan(timeoutMs = 100)
        val first = bulbs.observeBulbs().first().first()
        bulbs.rename(first.id, "Ceiling")

        discovery.scan(timeoutMs = 100)

        assertEquals("Ceiling", bulbs.observeBulbs().first().single { it.id == first.id }.name)
    }

    @Test
    fun scan_holdsTheMulticastLockOnlyWhileScanning(): Unit = runBlocking {
        discovery.scan(timeoutMs = 100)

        assertEquals(1, maxLocksHeld)
        assertEquals(0, locksHeld)
        assertFalse(discovery.isScanning.value)
    }
}
