package com.dungeonvibes.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dungeonvibes.R

/**
 * Whether the phone is on Wi-Fi, provided once at the app root so every screen can show the
 * "not on Wi-Fi" banner without each ViewModel watching the network. (A CompositionLocal is like
 * React context.)
 */
val LocalOnLocalNetwork = staticCompositionLocalOf { true }

/**
 * Screen frame: title bar, the Wi-Fi banner when needed, then content. The title bar ends with the
 * lights menu unless [lightsMenu] is false (a detail pane shown beside its list, which has one).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopLevelScreen(
    title: String,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    snackbarHostState: SnackbarHostState? = null,
    floatingActionButton: @Composable () -> Unit = {},
    lightsMenu: Boolean = true,
    content: @Composable (Modifier) -> Unit,
) {
    val onLocalNetwork = LocalOnLocalNetwork.current
    Scaffold(
        snackbarHost = { if (snackbarHostState != null) SnackbarHost(snackbarHostState) },
        floatingActionButton = floatingActionButton,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title) },
                navigationIcon = navigationIcon,
                actions = {
                    actions()
                    if (lightsMenu) LightsMenuButton()
                },
            )
        },
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding)) {
            if (!onLocalNetwork) NotOnWifiBanner()
            content(Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun NotOnWifiBanner() {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.banner_not_on_wifi),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/** Centered "nothing here yet" message used until each screen is built. */
@Composable
fun PlaceholderContent(
    message: String,
    phase: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.placeholder_coming_in_phase, phase),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
