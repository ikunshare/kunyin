package com.ikunshare.sound.ui.components

import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.ikunshare.sound.R
import java.io.File

/**
 * 应用内文件选择对话框，与 [FolderPickerDialog] 风格一致，但用于选取单个文件。
 *
 * - 同时展示子文件夹（可进入）与匹配 [extensions] 的文件（点击即选中返回）。
 * - [extensions] 为空时显示全部文件；扩展名不区分大小写，不含点（如 "json"）。
 * - 隐藏以 "." 开头的条目。返回所选文件的绝对路径。
 */
@Composable
fun FilePickerDialog(
    initialPath: String,
    extensions: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val rootPath = Environment.getExternalStorageDirectory().absolutePath
    val exts = remember(extensions) { extensions.map { it.lowercase() } }

    var currentDir by remember {
        val initial = File(initialPath)
        mutableStateOf(
            if (initial.isDirectory && initial.canRead()) initial
            else Environment.getExternalStorageDirectory()
        )
    }

    val folders by remember(currentDir) {
        derivedStateOf {
            currentDir.listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.sortedBy { it.name.lowercase() }
                ?: emptyList()
        }
    }

    val files by remember(currentDir, exts) {
        derivedStateOf {
            currentDir.listFiles()
                ?.filter { f ->
                    f.isFile && !f.name.startsWith(".") &&
                            (exts.isEmpty() || exts.any { f.name.lowercase().endsWith(".$it") })
                }
                ?.sortedBy { it.name.lowercase() }
                ?: emptyList()
        }
    }

    val canGoUp = currentDir.absolutePath != "/" && currentDir.absolutePath != rootPath

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    stringResource(R.string.select_file),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (canGoUp) {
                        IconButton(
                            onClick = { currentDir.parentFile?.let { currentDir = it } },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        currentDir.absolutePath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                if (!currentDir.canRead()) {
                    EmptyHint(R.string.folder_unreadable, isError = true)
                } else if (folders.isEmpty() && files.isEmpty()) {
                    EmptyHint(R.string.no_matching_files, isError = false)
                } else {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(folders, key = { "d:" + it.absolutePath }) { folder ->
                            PickerRow(
                                name = folder.name,
                                isFolder = true,
                                onClick = { currentDir = folder }
                            )
                        }
                        items(files, key = { "f:" + it.absolutePath }) { file ->
                            PickerRow(
                                name = file.name,
                                isFolder = false,
                                onClick = { onConfirm(file.absolutePath) }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            stringResource(R.string.cancel),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.EmptyHint(textRes: Int, isError: Boolean) {
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            stringResource(textRes),
            color = if (isError) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PickerRow(name: String, isFolder: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (isFolder) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = null,
            tint = if (isFolder) MaterialTheme.colorScheme.tertiary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
