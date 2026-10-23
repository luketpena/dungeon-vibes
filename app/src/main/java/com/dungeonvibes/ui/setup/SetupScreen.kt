package com.dungeonvibes.ui.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.dungeonvibes.R
import com.dungeonvibes.ui.common.TopLevelScreen
import com.dungeonvibes.ui.setup.bulbs.BulbsTab
import com.dungeonvibes.ui.setup.lighting.DefaultLightingTab
import com.dungeonvibes.ui.setup.slots.SlotsTab

private const val TAB_BULBS = 0
private const val TAB_SLOTS = 1
private const val TAB_DEFAULT = 2

/**
 * @param onOpenDebug null hides the debug button (release builds).
 * @param onOpenSceneEditor opens the default-lighting scene in the scene editor.
 * @param onOpenBulb opens a bulb's page.
 * @param onOpenGroup opens a group's page.
 */
@Composable
fun SetupScreen(
    onOpenDebug: (() -> Unit)?,
    onOpenSceneEditor: (Long) -> Unit,
    onOpenBulb: (Long) -> Unit,
    onOpenGroup: (Long) -> Unit,
) {
    // rememberSaveable survives rotation, unlike plain remember.
    var selectedTab by rememberSaveable { mutableIntStateOf(TAB_BULBS) }

    TopLevelScreen(
        title = stringResource(R.string.nav_setup),
        actions = {
            if (onOpenDebug != null) {
                IconButton(onClick = onOpenDebug) {
                    Icon(
                        painterResource(R.drawable.ic_debug),
                        contentDescription = stringResource(R.string.action_open_debug),
                    )
                }
            }
        },
    ) { modifier ->
        Column(modifier = modifier) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == TAB_BULBS,
                    onClick = { selectedTab = TAB_BULBS },
                    text = { Text(stringResource(R.string.setup_tab_bulbs)) },
                )
                Tab(
                    selected = selectedTab == TAB_SLOTS,
                    onClick = { selectedTab = TAB_SLOTS },
                    text = { Text(stringResource(R.string.setup_tab_slots)) },
                )
                Tab(
                    selected = selectedTab == TAB_DEFAULT,
                    onClick = { selectedTab = TAB_DEFAULT },
                    text = { Text(stringResource(R.string.setup_tab_default)) },
                )
            }
            when (selectedTab) {
                TAB_BULBS -> BulbsTab(onOpenBulb = onOpenBulb, modifier = Modifier.weight(1f))
                TAB_SLOTS -> SlotsTab(onOpenGroup = onOpenGroup, modifier = Modifier.weight(1f))
                else -> DefaultLightingTab(onOpenEditor = onOpenSceneEditor, modifier = Modifier.weight(1f))
            }
        }
    }
}
