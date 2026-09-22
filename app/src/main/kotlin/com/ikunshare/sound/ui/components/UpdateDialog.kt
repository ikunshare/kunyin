package com.ikunshare.sound.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ikunshare.sound.R
import com.ikunshare.sound.manager.UpdateInfo
import com.mikepenz.markdown.m3.Markdown

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpdateDialog(
    updateInfo: UpdateInfo,
    onDismiss: () -> Unit,
    onSkipVersion: () -> Unit,
    onExitApp: () -> Unit,
    onDownload: () -> Unit,
    onBrowserDownload: () -> Unit,
    downloadProgress: Float?,
    downloadFailed: Boolean
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val isForce = updateInfo.isCompulsory
    val hasDirectUrl = updateInfo.downloadUrl.isNotBlank()
    val isDownloading = downloadProgress != null && !downloadFailed

    AlertDialog(
        onDismissRequest = if (isForce) ({}) else onDismiss,
        title = {
            Text(
                text = stringResource(R.string.new_version_found, updateInfo.title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = screenHeight * 0.6f)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    Markdown(
                        content = updateInfo.log,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // 下载进度区域
                if (downloadProgress != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    if (downloadFailed) {
                        Text(
                            text = stringResource(R.string.download_failed_retry),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${(downloadProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            FlowRow(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth()
            ) {
                // 左侧按钮：不再提示此版本（非强制）/ 退出（强制）
                if (isForce) {
                    TextButton(
                        onClick = onExitApp,
                        enabled = !isDownloading
                    ) {
                        Text(
                            stringResource(R.string.exit_app),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                } else {
                    TextButton(
                        onClick = onSkipVersion,
                        enabled = !isDownloading
                    ) {
                        Text(
                            stringResource(R.string.skip_version),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    TextButton(
                        onClick = onDismiss,
                        enabled = !isDownloading
                    ) {
                        Text(
                            stringResource(R.string.cancel),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }

                // 直链更新按钮（仅 direct 非空时显示）
                if (hasDirectUrl) {
                    TextButton(
                        onClick = onDownload,
                        enabled = !isDownloading
                    ) {
                        Text(
                            stringResource(R.string.update_btn),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }

                // 浏览器更新按钮
                TextButton(
                    onClick = onBrowserDownload,
                    enabled = !isDownloading
                ) {
                    Text(
                        stringResource(R.string.browser_update),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        }
    )
}
