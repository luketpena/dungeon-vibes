package com.dungeonvibes

import android.os.Bundle
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
        enableEdgeToEdge()
        setContent {
            DungeonVibesTheme {
                DungeonVibesRoot()
            }
        }
    }
}
