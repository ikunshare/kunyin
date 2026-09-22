package com.ikunshare.sound.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.ikunshare.sound.R
import com.ikunshare.sound.SoundApplication
import com.ikunshare.sound.manager.CredentialEntry
import com.ikunshare.sound.platform.base.ProviderCredentials
import com.ikunshare.sound.platform.kg.KgCredentials

internal data class ProviderInfo(
    val key: String,
    val displayName: String,
    val supportsWebView: Boolean
)

private val ACCOUNT_VISIBLE_KEYS = setOf("qq", "wy", "kg")

internal val providerList: List<ProviderInfo> by lazy {
    val app = SoundApplication.instance ?: return@lazy emptyList()
    app.musicRepository.getAvailableProviders()
        .filter { it in ACCOUNT_VISIBLE_KEYS }
        .mapNotNull { key ->
            val provider = app.musicRepository.getProvider(key) ?: return@mapNotNull null
            ProviderInfo(key, provider.displayName, provider.supportsWebView)
        }
}

@Composable
internal fun AccountSection(
    creds: Map<String, CredentialEntry>,
    settings: com.ikunshare.sound.common.AppSettings,
    vm: SettingsViewModel,
    onProviderClick: (ProviderInfo) -> Unit
) {
    var reloginProvider by remember { mutableStateOf<ProviderInfo?>(null) }
    val platformPlaylistStore = SoundApplication.instance?.platformPlaylistStore

    SectionHeader(stringResource(R.string.section_account))
    providerList.forEach { info ->
        val isLoggedIn = creds.containsKey(info.key)
        val userInfo = platformPlaylistStore?.getUserInfo(info.key)
        val statusText = if (isLoggedIn && userInfo != null) {
            stringResource(R.string.logged_in_as, userInfo.name)
        } else if (isLoggedIn) {
            stringResource(R.string.logged_in_as, info.displayName)
        } else {
            stringResource(R.string.not_logged_in)
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clickable {
                    if (isLoggedIn) {
                        reloginProvider = info
                    } else {
                        onProviderClick(info)
                    }
                },
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        info.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isLoggedIn) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isLoggedIn) {
                    TextButton(onClick = { vm.clearCredential(info.key) }) {
                        Text(
                            stringResource(R.string.clear_credential),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }

    reloginProvider?.let { info ->
        val userInfo = platformPlaylistStore?.getUserInfo(info.key)
        val displayName = userInfo?.name ?: info.displayName
        AlertDialog(
            onDismissRequest = { reloginProvider = null },
            title = { Text(stringResource(R.string.relogin_confirm_title)) },
            text = { Text(stringResource(R.string.relogin_confirm_message, displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    reloginProvider = null
                    onProviderClick(info)
                }) {
                    Text(
                        stringResource(R.string.confirm),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { reloginProvider = null }) {
                    Text(
                        stringResource(R.string.cancel),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        )
    }
}

@Composable
internal fun QQLoginMethodDialog(
    onDismiss: () -> Unit,
    onQRLogin: () -> Unit,
    onWebViewLogin: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("QQ音乐") },
        text = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onQRLogin, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.login_qrcode))
                }
                OutlinedButton(onClick = onWebViewLogin, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.login_web))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
internal fun KgLoginMethodDialog(
    existing: CredentialEntry?,
    onDismiss: () -> Unit,
    onQRLogin: () -> Unit,
    onManualLogin: () -> Unit,
    onClear: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("酷狗音乐") },
        text = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onQRLogin, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.login_qrcode))
                }
                OutlinedButton(onClick = onManualLogin, modifier = Modifier.weight(1f)) {
                    Text("手动填写")
                }
            }
        },
        confirmButton = {
            if (existing?.credentials != null) {
                TextButton(onClick = onClear) {
                    Text(
                        stringResource(R.string.clear_credential),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
internal fun KgCredentialDialog(
    existing: CredentialEntry?,
    onDismiss: () -> Unit,
    onSave: (ProviderCredentials?) -> Unit,
    onClear: () -> Unit
) {
    var kgUserid by remember {
        mutableStateOf((existing?.credentials as? KgCredentials)?.userid ?: "")
    }
    var kgToken by remember {
        mutableStateOf((existing?.credentials as? KgCredentials)?.token ?: "")
    }
    var kgMid by remember {
        mutableStateOf((existing?.credentials as? KgCredentials)?.mid ?: "")
    }
    var kgDfid by remember {
        mutableStateOf((existing?.credentials as? KgCredentials)?.dfid ?: "")
    }
    var advancedExpanded by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(DialogCornerRadius),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp)
        ) {
            Column(Modifier.padding(24.dp)) {
                Text(
                    "酷狗音乐",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    StyledTextField(kgUserid, { kgUserid = it }, "userid")
                    Spacer(Modifier.height(8.dp))
                    StyledTextField(kgToken, { kgToken = it }, "token")
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { advancedExpanded = !advancedExpanded },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "mid / dfid",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.weight(1f))
                        Icon(
                            if (advancedExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    AnimatedVisibility(visible = advancedExpanded) {
                        Column {
                            Spacer(Modifier.height(8.dp))
                            StyledTextField(kgMid, { kgMid = it }, "mid")
                            Spacer(Modifier.height(8.dp))
                            StyledTextField(kgDfid, { kgDfid = it }, "dfid")
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (existing?.credentials != null) {
                        TextButton(onClick = onClear) {
                            Text(
                                stringResource(R.string.clear_credential),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    TextButton(onClick = onDismiss) {
                        Text(
                            stringResource(R.string.cancel),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    TextButton(onClick = {
                        val cred: ProviderCredentials? =
                            if (kgUserid.isNotBlank() && kgToken.isNotBlank())
                                KgCredentials(
                                    kgUserid,
                                    kgToken,
                                    kgMid.ifBlank { null },
                                    kgDfid.ifBlank { null }
                                )
                            else null
                        onSave(cred)
                    }) {
                        Text(
                            stringResource(R.string.save),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }
        }
    }
}

