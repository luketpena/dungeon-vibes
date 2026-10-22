package com.dungeonvibes.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class NavLayoutTest {

    @Test
    fun phonePortrait_usesBottomBar() {
        assertEquals(NavLayout.BottomBar, navLayoutFor(widthDp = 0, heightDp = 480))
    }

    @Test
    fun phoneLandscape_usesBottomBar() {
        // Wide but short: width class expanded, height class compact.
        assertEquals(NavLayout.BottomBar, navLayoutFor(widthDp = 840, heightDp = 0))
    }

    @Test
    fun tabletPortrait_usesRail() {
        assertEquals(NavLayout.Rail, navLayoutFor(widthDp = 600, heightDp = 900))
    }

    @Test
    fun tabletLandscape_usesRail() {
        assertEquals(NavLayout.Rail, navLayoutFor(widthDp = 840, heightDp = 480))
    }
}
