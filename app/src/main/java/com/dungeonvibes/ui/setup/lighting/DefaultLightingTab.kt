package com.dungeonvibes.ui.setup.lighting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.dungeonvibes.R
import com.dungeonvibes.data.scene.SceneRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class DefaultLightingViewModel @Inject constructor(private val scenes: SceneRepository) : ViewModel() {
    /** The default-lighting scene, created on first use. */
    suspend fun sceneId(): Long = scenes.ensureDefaultScene()
}

/** Setup's Default tab: what a neutral room looks like, edited with the scene editor. */
@Composable
fun DefaultLightingTab(
    onOpenEditor: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DefaultLightingViewModel = hiltViewModel(),
) {
    val scope = rememberCoroutineScope()
    Column(modifier.padding(16.dp)) {
        Text(stringResource(R.string.default_lighting), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.default_lighting_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
        Button(onClick = { scope.launch { onOpenEditor(viewModel.sceneId()) } }) {
            Text(stringResource(R.string.default_lighting_edit))
        }
    }
}
