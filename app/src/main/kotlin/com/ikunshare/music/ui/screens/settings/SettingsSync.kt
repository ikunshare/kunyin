package com.ikunshare.music.ui.screens.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ikunshare.music.R
import com.ikunshare.music.MusicApplication
import com.ikunshare.music.sync.SyncState
import com.ikunshare.music.ui.components.GlassButton
import com.ikunshare.music.ui.components.liquidGlass
import com.kyant.shapes.RoundedRectangle

@Composable
internal fun SyncSection() {
    val context = LocalContext.current
    val app = context.applicationContext as MusicApplication
    val manager = app.syncManager
    val status by manager.client.status.collectAsState()

    var serverUrl by remember { mutableStateOf(manager.state.serverUrl) }
    var cdk by remember { mutableStateOf(manager.state.cdk) }
    var deviceName by remember { mutableStateOf(manager.state.deviceName) }
    var syncMode by remember { mutableStateOf(manager.state.syncMode) }
    var autoConnect by remember { mutableStateOf(manager.state.autoConnect) }
    var modeMenuOpen by remember { mutableStateOf(false) }

    val persist: () -> Unit = {
        manager.state.serverUrl = serverUrl.trim()
        manager.state.cdk = cdk.trim()
        manager.state.deviceName = deviceName.trim().ifEmpty { android.os.Build.MODEL }
        manager.state.syncMode = syncMode
        manager.state.autoConnect = autoConnect
    }

    Column(
        modifier = Modifier
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            stringResource(R.string.sync_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text(stringResource(R.string.sync_server)) },
            placeholder = { Text(stringResource(R.string.sync_server_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().liquidGlass(RoundedRectangle(22.dp)),
            shape = RoundedRectangle(22.dp),
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent)
        )
        OutlinedTextField(
            value = cdk,
            onValueChange = { cdk = it },
            label = { Text(stringResource(R.string.sync_cdk)) },
            placeholder = { Text(stringResource(R.string.sync_cdk_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().liquidGlass(RoundedRectangle(22.dp)),
            shape = RoundedRectangle(22.dp),
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent)
        )
        OutlinedTextField(
            value = deviceName,
            onValueChange = { deviceName = it },
            label = { Text(stringResource(R.string.sync_device_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().liquidGlass(RoundedRectangle(22.dp)),
            shape = RoundedRectangle(22.dp),
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent)
        )

        // 同步模式下拉
        Column {
            Text(
                stringResource(R.string.sync_mode),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            GlassButton(
                onClick = { modeMenuOpen = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text(syncModeLabel(syncMode)) }
            DropdownMenu(
                expanded = modeMenuOpen,
                onDismissRequest = { modeMenuOpen = false }
            ) {
                syncModes.forEach { (value, labelRes) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(labelRes)) },
                        onClick = {
                            syncMode = value
                            manager.state.syncMode = value
                            modeMenuOpen = false
                        }
                    )
                }
            }
        }

        SettingSwitchItem(
            label = stringResource(R.string.sync_auto_connect),
            checked = autoConnect,
            onCheckedChange = {
                autoConnect = it
                manager.state.autoConnect = it
            }
        )

        // 状态行
        Text(
            text = when (val s = status) {
                SyncState.Idle -> stringResource(R.string.sync_status_idle)
                SyncState.Connecting -> stringResource(R.string.sync_status_connecting)
                SyncState.Syncing -> stringResource(R.string.sync_status_syncing)
                SyncState.Connected -> stringResource(R.string.sync_status_connected)
                is SyncState.Failed -> stringResource(R.string.sync_status_failed, s.message)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GlassButton(
                accented = true,
                onClick = {
                    persist()
                    if (manager.state.serverUrl.isBlank() || manager.state.cdk.isBlank()) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.sync_required_fields),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        manager.connect()
                    }
                },
                enabled = status !is SyncState.Connecting && status !is SyncState.Syncing,
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.sync_action_connect)) }

            GlassButton(
                onClick = { manager.disconnect() },
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.sync_action_disconnect)) }
        }

        GlassButton(
            onClick = {
                persist()
                manager.resetSession()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.sync_action_reset), color = MaterialTheme.colorScheme.error) }
    }
}

private val syncModes = listOf(
    "merge_local_remote" to R.string.sync_mode_merge_local_remote,
    "merge_remote_local" to R.string.sync_mode_merge_remote_local,
    "overwrite_local_remote" to R.string.sync_mode_overwrite_local_remote,
    "overwrite_remote_local" to R.string.sync_mode_overwrite_remote_local
)

@Composable
private fun syncModeLabel(value: String): String {
    val res = syncModes.firstOrNull { it.first == value }?.second
        ?: R.string.sync_mode_merge_local_remote
    return stringResource(res)
}
