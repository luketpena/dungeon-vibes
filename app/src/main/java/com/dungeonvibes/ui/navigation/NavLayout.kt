package com.dungeonvibes.ui.navigation

enum class NavLayout { BottomBar, Rail }

// Material window-size-class breakpoints, in dp.
private const val MEDIUM_WIDTH_DP = 600
private const val MEDIUM_HEIGHT_DP = 480

/**
 * Tablets get a navigation rail, phones a bottom bar, in either orientation.
 * A phone in landscape is wide but short, so requiring a medium height as well as a medium
 * width keeps it on the bottom bar, while a tablet clears both thresholds both ways round.
 */
fun navLayoutFor(widthDp: Int, heightDp: Int): NavLayout {
    if (widthDp < MEDIUM_WIDTH_DP) return NavLayout.BottomBar
    if (heightDp < MEDIUM_HEIGHT_DP) return NavLayout.BottomBar
    return NavLayout.Rail
}
