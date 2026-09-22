package com.ikunshare.sound.ui.screens.settings

import android.annotation.SuppressLint
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.ikunshare.sound.R
import com.ikunshare.sound.SoundApplication
import com.ikunshare.sound.sync.SyncState

@Composable
internal fun SyncSection() {
    val context = LocalContext.current
    val app = context.applicationContext as SoundApplication
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
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Sync, null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.sync_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
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
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = cdk,
            onValueChange = { cdk = it },
            label = { Text(stringResource(R.string.sync_cdk)) },
            placeholder = { Text(stringResource(R.string.sync_cdk_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = deviceName,
            onValueChange = { deviceName = it },
            label = { Text(stringResource(R.string.sync_device_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // 同步模式下拉
        Column {
            Text(
                stringResource(R.string.sync_mode),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
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
            Button(
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

            OutlinedButton(
                onClick = { manager.disconnect() },
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.sync_action_disconnect)) }
        }

        OutlinedButton(
            onClick = { requestIgnoreBatteryOptimization(context) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Text(stringResource(R.string.sync_battery_action))
                Text(
                    stringResource(R.string.sync_battery_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        OutlinedButton(
            onClick = {
                persist()
                manager.resetSession()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            )
        ) { Text(stringResource(R.string.sync_action_reset)) }
    }
}

@SuppressLint("BatteryLife")
private fun requestIgnoreBatteryOptimization(context: android.content.Context) {
    val pm =
        context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
    if (pm == null) {
        Toast.makeText(
            context,
            context.getString(R.string.sync_battery_unsupported),
            Toast.LENGTH_SHORT
        ).show()
        return
    }
    val pkg = context.packageName
    if (pm.isIgnoringBatteryOptimizations(pkg)) {
        Toast.makeText(
            context,
            context.getString(R.string.sync_battery_already),
            Toast.LENGTH_SHORT
        ).show()
        return
    }
    val intent = android.content.Intent(
        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        "package:$pkg".toUri()
    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        // 部分 ROM 不允许带 data 直跳，退回系统电池优化列表页
        val fallback = android.content.Intent(
            android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(fallback) }.onFailure {
            Toast.makeText(
                context,
                context.getString(R.string.sync_battery_unsupported),
                Toast.LENGTH_SHORT
            ).show()
        }
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
