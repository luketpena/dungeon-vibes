package com.dungeonvibes.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.dungeonvibes.light.CommandedLight
import com.dungeonvibes.light.LocalNetworkPermission
import com.dungeonvibes.light.fx.NotificationPermission
import com.dungeonvibes.ui.adventures.play.RoomGlowEdge
import com.dungeonvibes.ui.common.LocalOnLocalNetwork
import com.dungeonvibes.ui.navigation.AppNavHost
import com.dungeonvibes.ui.navigation.NavLayout
import com.dungeonvibes.ui.navigation.TopLevelDestination
import com.dungeonvibes.ui.navigation.navLayoutFor
import kotlinx.coroutines.flow.StateFlow

// Allocated once instead of on every recomposition.
private val topLevelDestinations = TopLevelDestination.entries

@Composable
fun DungeonVibesRoot(viewModel: MainViewModel = hiltViewModel()) {
    val startDestination by viewModel.startDestination.collectAsStateWithLifecycle()
    val onLocalNetwork by viewModel.isOnLocalNetwork.collectAsStateWithLifecycle()

    // Android 17+ asks the user before the app may talk to devices on the local network. Ask once
    // per launch (rememberSaveable keeps the flag across rotation), then run the startup scan.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.startupScan()
    }
    var askedForPermission by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!viewModel.needsLocalNetworkPermission) {
            viewModel.startupScan()
            return@LaunchedEffect
        }
        if (askedForPermission) return@LaunchedEffect
        askedForPermission = true
        permissionLauncher.launch(LocalNetworkPermission.NAME)
    }

    // The first time effects start, ask to show their "running" notification (Android 13+).
    // Effects run whether or not it's granted, so the answer needs no handling.
    val effectsRunning by viewModel.effectsRunning.collectAsStateWithLifecycle()
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(effectsRunning) {
        if (effectsRunning && viewModel.takeNotificationPermissionAsk()) {
            notificationLauncher.launch(NotificationPermission.NAME)
        }
    }

    val roomBulbs by viewModel.roomBulbs.collectAsStateWithLifecycle()
    val observeLight: (String) -> StateFlow<CommandedLight?> = remember(viewModel) { viewModel::observeLight }

    // Nothing to draw until we know which screen to open on.
    val start = startDestination ?: return

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    val sizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
    val navLayout = navLayoutFor(sizeClass.minWidthDp, sizeClass.minHeightDp)
    val suiteType = remember(navLayout) {
        when (navLayout) {
            NavLayout.BottomBar -> NavigationSuiteType.NavigationBar
            NavLayout.Rail -> NavigationSuiteType.NavigationRail
        }
    }

    NavigationSuiteScaffold(
        layoutType = suiteType,
        navigationSuiteItems = {
            for (destination in topLevelDestinations) {
                // Sub-screens like "adventures/scene/12" keep their tab highlighted.
                val selected = currentDestination?.hierarchy?.any { it.route.belongsTo(destination) } == true
                item(
                    selected = selected,
                    onClick = { navController.navigateToTopLevel(destination) },
                    icon = { Icon(painterResource(destination.iconRes), contentDescription = null) },
                    label = { Text(stringResource(destination.labelRes)) },
                )
            }
        },
    ) {
        val alongBottom = navLayout == NavLayout.BottomBar
        Box(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalOnLocalNetwork provides onLocalNetwork) {
                AppNavHost(navController = navController, startDestination = start)
            }
            // The room's light along the nav, over every screen (drawn last, so it's on top).
            RoomGlowEdge(
                bulbs = roomBulbs,
                effectsRunning = effectsRunning,
                alongBottom = alongBottom,
                observe = observeLight,
                modifier = Modifier.align(if (alongBottom) Alignment.BottomCenter else Alignment.CenterStart),
            )
        }
    }
}

/**
 * Standard bottom-bar behavior: each tab keeps its own back stack, re-tapping the current tab
 * doesn't stack a duplicate, and Back from any tab returns to the start tab before exiting.
 */
private fun NavHostController.navigateToTopLevel(destination: TopLevelDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun String?.belongsTo(destination: TopLevelDestination): Boolean {
    if (this == null) return false
    return this == destination.route || startsWith(destination.route + "/")
}
