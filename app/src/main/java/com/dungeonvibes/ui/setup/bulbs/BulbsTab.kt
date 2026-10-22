package com.dungeonvibes.ui.setup.bulbs

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dungeonvibes.R
import com.dungeonvibes.ui.common.NameKeyboardOptions
import com.dungeonvibes.data.db.BulbEntity
import com.dungeonvibes.light.LocalNetworkPermission
import com.dungeonvibes.ui.common.ConfirmDialog

private val OnlineColor = Color(0xFF43A047)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BulbsTab(
    modifier: Modifier = Modifier,
    viewModel: BulbsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val onLocalNetwork by viewModel.isOnLocalNetwork.collectAsStateWithLifecycle()
    val hasPermission by viewModel.hasPermission.collectAsStateWithLifecycle()

    // Scan whenever the tab opens, and re-check the permission when coming back from Settings.
    LaunchedEffect(Unit) { viewModel.scan() }
    LifecycleResumeEffect(Unit) {
        viewModel.refreshPermission()
        onPauseOrDispose { }
    }

    var editing by remember { mutableStateOf<BulbEntity?>(null) }

    PullToRefreshBox(
        isRefreshing = state.isScanning,
        onRefresh = viewModel::scan,
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            if (!hasPermission) {
                item(key = "permission") {
                    PermissionCard(onGranted = viewModel::scan)
                }
            } else if (state.bulbs.isEmpty() && state.hasScanned && !state.isScanning && onLocalNetwork) {
                item(key = "empty") { EmptyState() }
            }
            items(state.bulbs, key = { it.bulb.id }) { item ->
                BulbRow(item = item, onClick = { editing = item.bulb })
                HorizontalDivider()
            }
        }
    }

    editing?.let { bulb ->
        BulbDialog(
            bulb = bulb,
            onRename = { name -> viewModel.rename(bulb, name) },
            onIdentify = { viewModel.identify(bulb) },
            onRemove = { viewModel.remove(bulb) },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun BulbRow(item: BulbItem, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Box(
            Modifier
                .size(10.dp)
                .background(if (item.isOnline) OnlineColor else MaterialTheme.colorScheme.outline, CircleShape),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(item.bulb.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(
                    R.string.bulb_status,
                    stringResource(if (item.isOnline) R.string.bulb_online else R.string.bulb_offline),
                    item.slotName ?: stringResource(R.string.bulb_no_slot),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Rename, Identify and Remove for one bulb. */
@Composable
private fun BulbDialog(
    bulb: BulbEntity,
    onRename: (String) -> Unit,
    onIdentify: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(bulb.id) { mutableStateOf(bulb.name) }
    var confirmRemove by rememberSaveable(bulb.id) { mutableStateOf(false) }
    val trimmed = name.trim()

    if (confirmRemove) {
        ConfirmDialog(
            title = stringResource(R.string.bulb_remove_title, bulb.name),
            message = stringResource(R.string.bulb_remove_message),
            confirmLabel = stringResource(R.string.action_remove),
            onConfirm = {
                onRemove()
                onDismiss()
            },
            onDismiss = { confirmRemove = false },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bulb_dialog_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.label_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = NameKeyboardOptions,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onIdentify) { Text(stringResource(R.string.action_identify)) }
                    OutlinedButton(onClick = { confirmRemove = true }) { Text(stringResource(R.string.action_remove)) }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (trimmed != bulb.name) onRename(trimmed)
                    onDismiss()
                },
                enabled = trimmed.isNotEmpty(),
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun EmptyState() {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.bulbs_empty_title), style = MaterialTheme.typography.titleMedium)
        Text("• " + stringResource(R.string.bulbs_empty_check_app), style = MaterialTheme.typography.bodyMedium)
        Text("• " + stringResource(R.string.bulbs_empty_check_wifi), style = MaterialTheme.typography.bodyMedium)
        Text("• " + stringResource(R.string.bulbs_empty_check_guest), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(R.string.bulbs_empty_pull),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Shown on Android 17+ when local network access hasn't been allowed. */
@Composable
private fun PermissionCard(onGranted: () -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onGranted()
    }
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.permission_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.permission_message), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { launcher.launch(LocalNetworkPermission.NAME) }) {
                Text(stringResource(R.string.permission_allow))
            }
            // After two denials Android stops showing the prompt, so offer the Settings page too.
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                    )
                },
            ) { Text(stringResource(R.string.permission_open_settings)) }
        }
    }
}
