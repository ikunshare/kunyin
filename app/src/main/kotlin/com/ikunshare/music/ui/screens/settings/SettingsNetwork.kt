package com.ikunshare.music.ui.screens.settings

import com.ikunshare.music.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ikunshare.music.common.AppSettings
import com.ikunshare.music.ui.components.GlassButton
import com.ikunshare.music.utils.*

@Composable
internal fun NetworkSettingsSection(settings: AppSettings, vm: SettingsViewModel) {
    val status by NetworkProxy.routing.status.collectAsState()
    var host by remember(settings.proxyHost) { mutableStateOf(settings.proxyHost) }
    var port by remember(settings.proxyPort) { mutableStateOf(settings.proxyPort.takeIf { it > 0 }?.toString().orEmpty()) }
    val parsedPort = port.toIntOrNull()
    val valid = host.trim().isNotEmpty() && host.trim().none { it.isWhitespace() || it == '/' } && parsedPort != null && parsedPort in 1..65535
    SettingSwitchItem(stringResource(R.string.proxy_enable), settings.proxyEnabled) {
        vm.updateSettings { copy(proxyEnabled = it) }
    }
    if (settings.proxyEnabled) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(host, { host = it }, label = { Text(stringResource(R.string.proxy_host)) },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(port, { port = it }, label = { Text(stringResource(R.string.proxy_port)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true, modifier = Modifier.fillMaxWidth())
            GlassButton(onClick = { vm.updateSettings { copy(proxyHost = host.trim(), proxyPort = parsedPort!!) } }, enabled = valid) {
                Text(stringResource(R.string.proxy_apply))
            }
            Text(stringResource(when (status.phase) {
                ProxyPhase.DISABLED -> R.string.proxy_disabled
                ProxyPhase.CHECKING -> R.string.proxy_checking
                ProxyPhase.INVALID -> R.string.proxy_invalid
                ProxyPhase.UNREACHABLE -> R.string.proxy_unreachable
                ProxyPhase.ACTIVE -> R.string.proxy_active
            }), style = MaterialTheme.typography.bodySmall,
                color = if (status.phase == ProxyPhase.UNREACHABLE || status.phase == ProxyPhase.INVALID)
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${status.configuration.host}:${status.configuration.port}", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { NetworkProxy.routing.configure(status.configuration, force = true) }) {
                Text(stringResource(R.string.retry))
            }
        }
    }
}
