package com.dungeonvibes.data.bulb

import com.dungeonvibes.data.DatabaseTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BulbRepositoryTest : DatabaseTest() {

    private lateinit var bulbs: BulbRepository

    @Before
    fun setUp() {
        bulbs = BulbRepository(db, time)
    }

    @Test
    fun hasAnyBulbs_reflectsTable(): Unit = runBlocking {
        assertFalse(bulbs.hasAnyBulbs())
        bulbs.recordSeen("fake", "aa", "10.0.0.1", "Bulb")
        assertTrue(bulbs.hasAnyBulbs())
    }

    @Test
    fun recordSeen_matchesBySerial_updatesIpAndKeepsName(): Unit = runBlocking {
        val id = bulbs.recordSeen("fake", "aa", "10.0.0.1", "Default")
        bulbs.rename(id, "Ceiling left")

        val again = bulbs.recordSeen("fake", "aa", "10.0.0.77", "Default")

        assertEquals(id, again)
        val bulb = bulbs.observeBulbs().first().single()
        assertEquals("10.0.0.77", bulb.lastKnownIp)
        assertEquals("Ceiling left", bulb.name)
    }

    @Test
    fun sameSerialOnDifferentProtocols_areDifferentBulbs(): Unit = runBlocking {
        val a = bulbs.recordSeen("lifx_lan", "aa", "10.0.0.1", "A")
        val b = bulbs.recordSeen("matter", "aa", "10.0.0.1", "B")

        assertTrue(a != b)
        assertEquals(2, bulbs.observeBulbs().first().size)
    }
}
