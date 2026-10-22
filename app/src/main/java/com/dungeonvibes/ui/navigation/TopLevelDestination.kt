package com.dungeonvibes.ui.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.dungeonvibes.R

enum class TopLevelDestination(
    val route: String,
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int,
) {
    Adventures("adventures", R.string.nav_adventures, R.drawable.ic_nav_adventures),
    Scenes("scenes", R.string.nav_scenes, R.drawable.ic_nav_scenes),
    Setup("setup", R.string.nav_setup, R.drawable.ic_nav_setup),
}

/** First screen: Setup until at least one bulb is known, Adventures after that. */
fun startDestinationFor(hasAnyBulbs: Boolean): TopLevelDestination =
    if (hasAnyBulbs) TopLevelDestination.Adventures else TopLevelDestination.Setup
