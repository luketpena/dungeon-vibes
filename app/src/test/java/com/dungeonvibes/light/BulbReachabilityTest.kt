package com.dungeonvibes.light

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BulbReachabilityTest {

    @Test
    fun beforeAnyScan_nothingIsUnreachable() {
        assertFalse(BulbReachability().state.value.isUnreachable(1))
    }

    @Test
    fun aBulbMissingFromTheScan_isUnreachableUntilItAnswersAScene() {
        val reach = BulbReachability()
        reach.onScan(setOf(1L))
        assertTrue(reach.state.value.isUnreachable(2))

        reach.onApply(listOf(1L, 2L), failed = emptyList())
        assertFalse(reach.state.value.isUnreachable(2))
    }

    @Test
    fun missingAScene_isUnreachableUntilTheNextAnswer() {
        val reach = BulbReachability()
        reach.onScan(setOf(1L, 2L))
        reach.onApply(listOf(1L, 2L), failed = listOf(2L))
        assertTrue(reach.state.value.isUnreachable(2))

        // Found by a scan again.
        reach.onScan(setOf(1L, 2L))
        assertFalse(reach.state.value.isUnreachable(2))
    }
}
