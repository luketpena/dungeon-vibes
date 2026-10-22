package com.dungeonvibes.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.dungeonvibes.BuildConfig
import com.dungeonvibes.ui.adventures.AdventuresScreen
import com.dungeonvibes.ui.debug.DebugScreen
import com.dungeonvibes.ui.scenes.ScenesScreen
import com.dungeonvibes.ui.scenes.editor.SceneEditor
import com.dungeonvibes.ui.setup.SetupScreen

private const val ROUTE_DEBUG = "debug"
private const val ARG_SCENE_ID = "sceneId"
private const val ROUTE_ADVENTURE_SCENE = "adventures/scene/{$ARG_SCENE_ID}"

private fun adventureSceneRoute(sceneId: Long) = "adventures/scene/$sceneId"

/** The default-lighting scene's editor, full screen, under the Setup tab. */
private const val ROUTE_SETUP_SCENE = "setup/scene/{$ARG_SCENE_ID}"

private fun setupSceneRoute(sceneId: Long) = "setup/scene/$sceneId"

@Composable
fun AppNavHost(
    navController: NavHostController,
    startDestination: TopLevelDestination,
) {
    NavHost(navController = navController, startDestination = startDestination.route) {
        composable(TopLevelDestination.Adventures.route) {
            AdventuresScreen(onOpenSceneEditor = { sceneId -> navController.navigate(adventureSceneRoute(sceneId)) })
        }
        // An adventure scene's editor, full screen, under the Adventures tab.
        composable(
            route = ROUTE_ADVENTURE_SCENE,
            arguments = listOf(navArgument(ARG_SCENE_ID) { type = NavType.LongType }),
        ) { entry ->
            val sceneId = entry.arguments?.getLong(ARG_SCENE_ID) ?: return@composable
            SceneEditor(sceneId = sceneId, showBack = true, onBack = { navController.popBackStack() })
        }
        composable(TopLevelDestination.Scenes.route) { ScenesScreen() }
        composable(TopLevelDestination.Setup.route) {
            SetupScreen(
                onOpenDebug = if (BuildConfig.DEBUG) {
                    { navController.navigate(ROUTE_DEBUG) }
                } else {
                    null
                },
                onOpenSceneEditor = { sceneId -> navController.navigate(setupSceneRoute(sceneId)) },
            )
        }
        composable(
            route = ROUTE_SETUP_SCENE,
            arguments = listOf(navArgument(ARG_SCENE_ID) { type = NavType.LongType }),
        ) { entry ->
            val sceneId = entry.arguments?.getLong(ARG_SCENE_ID) ?: return@composable
            SceneEditor(sceneId = sceneId, showBack = true, onBack = { navController.popBackStack() })
        }
        // Only registered in debug builds, so release builds can't reach it at all.
        if (BuildConfig.DEBUG) {
            composable(ROUTE_DEBUG) { DebugScreen(onBack = { navController.popBackStack() }) }
        }
    }
}
