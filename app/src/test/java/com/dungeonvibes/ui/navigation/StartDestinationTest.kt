package com.dungeonvibes.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class StartDestinationTest {

    @Test
    fun noBulbs_opensSetup() {
        assertEquals(TopLevelDestination.Setup, startDestinationFor(hasAnyBulbs = false))
    }

    @Test
    fun withBulbs_opensAdventures() {
        assertEquals(TopLevelDestination.Adventures, startDestinationFor(hasAnyBulbs = true))
    }
}
