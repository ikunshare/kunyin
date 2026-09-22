package com.ikunshare.sound.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.ikunshare.sound.R
import com.ikunshare.sound.manager.AuthState

/**
 * 启动时强制激活弹窗：用户必须输入有效卡密才能进入应用。
 * 不响应点击外部/返回键取消；dismiss 回调仅保留给必要的退出链路（当前未使用）。
 */
@Composable
fun ActivationDialog(
    authState: AuthState,
    onSubmit: (String) -> Unit,
    onExit: () -> Unit,
    onClearError: () -> Unit
) {
    var input by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { /* 首次启动必须激活，忽略外部点击 */ },
        title = {
            Text(
                text = stringResource(R.string.activation_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.activation_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        if (authState.errorMessage.isNotEmpty()) onClearError()
                    },
                    label = { Text(stringResource(R.string.activation_input_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    isError = authState.errorMessage.isNotEmpty(),
                    supportingText = if (authState.errorMessage.isNotEmpty()) {
                        { Text(authState.errorMessage) }
                    } else null,
                    enabled = !authState.isChecking
                )
                if (authState.isChecking) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(
                            stringResource(R.string.activation_validating),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (input.isNotBlank()) onSubmit(input.trim()) },
                enabled = input.isNotBlank() && !authState.isChecking
            ) {
                Text(
                    stringResource(R.string.activation_confirm),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onExit) {
                Text(
                    stringResource(R.string.exit_app),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    )
}
