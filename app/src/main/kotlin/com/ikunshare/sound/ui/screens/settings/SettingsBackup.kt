package com.ikunshare.sound.ui.screens.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ikunshare.sound.R
import com.ikunshare.sound.manager.BackupData
import com.ikunshare.sound.manager.LxImportData
import com.ikunshare.sound.manager.RestoreOptions
import com.ikunshare.sound.ui.components.FilePickerDialog
import com.ikunshare.sound.ui.components.FolderPickerDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun BackupSection(vm: SettingsViewModel) {
    val context = LocalContext.current
    val isBusy by vm.isBackupBusy.collectAsState()
    val playlists by vm.playlists.collectAsState()
    val favorites by vm.localMusicStore.favoritesFlow.collectAsState()

    // 歌单选择对话框状态
    var showPlaylistPicker by remember { mutableStateOf(false) }

    // 恢复预览对话框
    var restorePreview by remember { mutableStateOf<BackupData?>(null) }

    // LX Music 导入预览
    var lxPreview by remember { mutableStateOf<LxImportData?>(null) }

    // 待导出的歌单选择结果
    var pendingExportFavorites by remember { mutableStateOf(true) }
    var pendingExportTrial by remember { mutableStateOf(true) }
    val pendingExportIds = remember { mutableStateListOf<Long>() }

    val dateStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())

    // 应用内文件/文件夹选择对话框状态
    // 导出：选择目录后写入对应文件；用待执行动作区分完整备份 / 歌单导出
    var exportAction by remember { mutableStateOf<ExportAction?>(null) }
    // 导入：选择文件后解析；用待执行动作区分恢复 / LX 导入
    var importAction by remember { mutableStateOf<ImportAction?>(null) }

    val defaultDir = remember {
        android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        ).absolutePath
    }

    Column {
        SectionHeader(stringResource(R.string.section_backup))

        if (isBusy) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(32.dp), contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            // 完整备份
            BackupActionRow(
                icon = Icons.Filled.Archive,
                title = stringResource(R.string.backup_full),
                subtitle = stringResource(R.string.backup_full_desc),
                onClick = {
                    exportAction = ExportAction.Full("kunyin_backup_$dateStr.json")
                }
            )

            Spacer(Modifier.height(8.dp))

            // 导出歌单
            BackupActionRow(
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                title = stringResource(R.string.backup_playlists),
                subtitle = stringResource(R.string.backup_playlists_desc),
                onClick = { showPlaylistPicker = true }
            )

            Spacer(Modifier.height(24.dp))

            // 从文件恢复
            BackupActionRow(
                icon = Icons.Filled.Restore,
                title = stringResource(R.string.backup_restore),
                subtitle = stringResource(R.string.backup_restore_desc),
                onClick = { importAction = ImportAction.Restore }
            )

            Spacer(Modifier.height(8.dp))

            // 导入 LX Music 歌单
            BackupActionRow(
                icon = Icons.Filled.FileDownload,
                title = stringResource(R.string.backup_import_lx),
                subtitle = stringResource(R.string.backup_import_lx_desc),
                onClick = { importAction = ImportAction.Lx }
            )
        }
    }

    // 歌单选择对话框
    if (showPlaylistPicker) {
        PlaylistExportDialog(
            favoritesCount = favorites.size,
            playlists = playlists,
            onDismiss = { showPlaylistPicker = false },
            onConfirm = { includeFav, includeTrial, ids ->
                showPlaylistPicker = false
                pendingExportFavorites = includeFav
                pendingExportTrial = includeTrial
                pendingExportIds.clear()
                pendingExportIds.addAll(ids)
                exportAction = ExportAction.Playlists("kunyin_playlists_$dateStr.json")
            }
        )
    }

    // 恢复预览对话框
    restorePreview?.let { data ->
        RestorePreviewDialog(
            data = data,
            onDismiss = { restorePreview = null },
            onConfirm = { options ->
                restorePreview = null
                vm.executeRestore(context, data, options) { result ->
                    if (result != null) {
                        val msg = buildString {
                            append(context.getString(R.string.backup_restore_success))
                            val parts = mutableListOf<String>()
                            if (result.settingsRestored) parts.add(context.getString(R.string.backup_settings_item))
                            if (result.themesAdded > 0) parts.add(
                                "${result.themesAdded} ${
                                    context.getString(
                                        R.string.backup_themes_unit
                                    )
                                }"
                            )
                            if (result.favoritesAdded > 0) parts.add(
                                "${result.favoritesAdded} ${
                                    context.getString(
                                        R.string.backup_favorites_unit
                                    )
                                }"
                            )
                            if (result.playlistsCreated > 0) parts.add(
                                "${result.playlistsCreated} ${
                                    context.getString(
                                        R.string.backup_playlists_unit
                                    )
                                }"
                            )
                            if (parts.isNotEmpty()) append("\n${parts.joinToString("、")}")
                            if (result.songsSkipped > 0) append(
                                "\n${
                                    context.getString(
                                        R.string.backup_songs_skipped,
                                        result.songsSkipped
                                    )
                                }"
                            )
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(
                            context,
                            context.getString(R.string.backup_restore_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }

    // LX Music 导入预览
    lxPreview?.let { data ->
        LxImportPreviewDialog(
            data = data,
            onDismiss = { lxPreview = null },
            onConfirm = {
                lxPreview = null
                vm.executeLxImport(data) { result ->
                    val msg = if (result != null) {
                        context.getString(
                            R.string.backup_lx_result,
                            result.songsAdded,
                            result.songsSkipped
                        )
                    } else {
                        context.getString(R.string.backup_restore_failed)
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    // 导出：选择目标文件夹
    exportAction?.let { action ->
        FolderPickerDialog(
            initialPath = defaultDir,
            onDismiss = { exportAction = null },
            onConfirm = { dir ->
                exportAction = null
                when (action) {
                    is ExportAction.Full ->
                        vm.exportFullBackup(context, dir, action.fileName) { _, msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        }

                    is ExportAction.Playlists ->
                        vm.exportPlaylistsBackup(
                            context, dir, action.fileName,
                            pendingExportFavorites, pendingExportTrial, pendingExportIds.toList()
                        ) { _, msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        }
                }
            }
        )
    }

    // 导入：选择源文件
    importAction?.let { action ->
        FilePickerDialog(
            initialPath = defaultDir,
            extensions = when (action) {
                ImportAction.Restore -> listOf("json")
                ImportAction.Lx -> listOf("json", "lxmc", "lxbk", "gz")
            },
            onDismiss = { importAction = null },
            onConfirm = { path ->
                importAction = null
                when (action) {
                    ImportAction.Restore -> vm.parseBackupFile(path) { data ->
                        if (data != null) {
                            restorePreview = data
                        } else {
                            Toast.makeText(
                                context,
                                context.getString(R.string.backup_invalid_file),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }

                    ImportAction.Lx -> vm.parseLxFile(path) { data ->
                        if (data != null) {
                            lxPreview = data
                        } else {
                            Toast.makeText(
                                context,
                                context.getString(R.string.backup_lx_invalid),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        )
    }
}

/** 备份导出待执行动作，携带默认文件名。 */
private sealed interface ExportAction {
    val fileName: String

    data class Full(override val fileName: String) : ExportAction
    data class Playlists(override val fileName: String) : ExportAction
}

/** 导入待执行动作。 */
private sealed interface ImportAction {
    data object Restore : ImportAction
    data object Lx : ImportAction
}

@Composable
private fun LxImportPreviewDialog(
    data: LxImportData,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Text(
                stringResource(R.string.backup_lx_preview_title),
                color = MaterialTheme.colorScheme.onBackground
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(
                        R.string.backup_lx_summary,
                        data.totalSongs(),
                        data.playlists.size
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                    items(data.playlists, key = { it.rawId }) { pl ->
                        Text(
                            "${pl.displayName} (${pl.songs.size})",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(R.string.backup_lx_import_btn),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(R.string.cancel),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    )
}

@Composable
private fun BackupActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.tertiary
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PlaylistExportDialog(
    favoritesCount: Int,
    playlists: List<com.ikunshare.sound.database.Playlist>,
    onDismiss: () -> Unit,
    onConfirm: (includeFavorites: Boolean, includeTrial: Boolean, playlistIds: List<Long>) -> Unit
) {
    // 系统歌单（我的收藏 / 试听列表）不作为普通歌单选项：
    // 收藏、试听列表各由顶部独立开关导出。
    val userPlaylists = remember(playlists) { playlists.filter { !it.isSystem } }
    val trialCount = remember(playlists) {
        playlists.firstOrNull { it.systemKind == "trial" }?.songCount ?: 0
    }
    var includeFavorites by remember { mutableStateOf(true) }
    var includeTrial by remember { mutableStateOf(true) }
    val selectedIds = remember {
        mutableStateListOf<Long>().also { it.addAll(userPlaylists.map { p -> p.id }) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Text(
                stringResource(R.string.backup_select_playlists),
                color = MaterialTheme.colorScheme.onBackground
            )
        },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item(key = "favorites") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { includeFavorites = !includeFavorites },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = includeFavorites,
                            onCheckedChange = { includeFavorites = it })
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${stringResource(R.string.favorite_songs)} ($favoritesCount)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
                if (trialCount > 0) {
                    item(key = "trial") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { includeTrial = !includeTrial },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = includeTrial,
                                onCheckedChange = { includeTrial = it })
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${stringResource(R.string.backup_trial_list)} ($trialCount)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                }
                items(items = userPlaylists, key = { it.id }) { pl ->
                    val checked = pl.id in selectedIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (checked) selectedIds.remove(pl.id) else selectedIds.add(pl.id)
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = checked, onCheckedChange = {
                            if (it) selectedIds.add(pl.id) else selectedIds.remove(pl.id)
                        })
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${pl.name} (${pl.songCount})",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        includeFavorites,
                        includeTrial && trialCount > 0,
                        selectedIds.toList()
                    )
                },
                enabled = includeFavorites || (includeTrial && trialCount > 0) || selectedIds.isNotEmpty()
            ) {
                Text(
                    stringResource(R.string.backup_export_btn),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(R.string.cancel),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    )
}

@Composable
private fun RestorePreviewDialog(
    data: BackupData,
    onDismiss: () -> Unit,
    onConfirm: (RestoreOptions) -> Unit
) {
    var restoreSettings by remember { mutableStateOf(true) }
    var restoreThemes by remember { mutableStateOf(true) }
    var restoreHistory by remember { mutableStateOf(true) }
    var restorePlaylists by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Text(
                stringResource(R.string.backup_restore_title),
                color = MaterialTheme.colorScheme.onBackground
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 备份信息
                Text(
                    "${
                        if (data.isFullBackup) stringResource(R.string.backup_type_full) else stringResource(
                            R.string.backup_type_playlists
                        )
                    } · ${data.formattedDate()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    data.summary(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(8.dp))

                // 恢复选项
                if (data.settings != null) {
                    RestoreOptionRow(
                        stringResource(R.string.backup_restore_settings),
                        restoreSettings
                    ) { restoreSettings = it }
                }
                if (!data.customThemes.isNullOrEmpty()) {
                    RestoreOptionRow(
                        stringResource(R.string.backup_restore_themes),
                        restoreThemes
                    ) { restoreThemes = it }
                }
                if (!data.searchHistory.isNullOrEmpty()) {
                    RestoreOptionRow(
                        stringResource(R.string.backup_restore_history),
                        restoreHistory
                    ) { restoreHistory = it }
                }
                if (!data.favorites.isNullOrEmpty() || !data.playlists.isNullOrEmpty()) {
                    RestoreOptionRow(
                        stringResource(R.string.backup_restore_playlists),
                        restorePlaylists
                    ) { restorePlaylists = it }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    RestoreOptions(
                        restoreSettings,
                        restoreThemes,
                        restoreHistory,
                        restorePlaylists
                    )
                )
            }) {
                Text(
                    stringResource(R.string.backup_restore_btn),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(R.string.cancel),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    )
}

@Composable
private fun RestoreOptionRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Switch(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}
