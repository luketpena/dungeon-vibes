package com.dungeonvibes

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.dungeonvibes.ui.DungeonVibesRoot
import com.dungeonvibes.ui.theme.DungeonVibesTheme
import dagger.hilt.android.AndroidEntryPoint

// The app's single Activity. Everything else is Compose screens swapped by Navigation Compose,
// much like a single-page web app with a client-side router.
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always dark, so the status and navigation bars always get light icons
        // (otherwise a phone in light mode would draw dark icons on the indigo).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            DungeonVibesTheme {
                DungeonVibesRoot()
            }
        }
    }
}
