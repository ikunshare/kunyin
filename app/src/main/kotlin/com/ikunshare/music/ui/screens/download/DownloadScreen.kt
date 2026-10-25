package com.ikunshare.music.ui.screens.download

import com.ikunshare.music.R
import android.annotation.SuppressLint
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.ikunshare.music.ui.KunyinViewModelFactory
import com.ikunshare.music.ui.components.EmptyState
import com.ikunshare.music.ui.components.GlassAlertDialog
import com.ikunshare.music.ui.components.GlassTopBar
import com.ikunshare.music.ui.components.LiquidGlassScene
import com.ikunshare.music.ui.components.liquidGlass
import com.ikunshare.music.ui.utils.LocalFloatingBottomBarReserve
import com.ikunshare.music.ui.utils.LocalScrollOffsetReporter
import com.kyant.shapes.Capsule

private val categories = listOf(
    DownloadCategory.All, DownloadCategory.Downloading, DownloadCategory.Waiting,
    DownloadCategory.Paused, DownloadCategory.Completed, DownloadCategory.Failed
)

private fun categoryIcon(category: DownloadCategory): ImageVector = when (category) {
    DownloadCategory.All -> Icons.Filled.Download
    DownloadCategory.Completed -> Icons.Filled.DownloadDone
    DownloadCategory.Downloading -> Icons.Filled.CloudDownload
    DownloadCategory.Waiting -> Icons.Filled.Schedule
    DownloadCategory.Paused -> Icons.Filled.Pause
    DownloadCategory.Failed -> Icons.Filled.ErrorOutline
}

private fun categoryEmptyMessage(category: DownloadCategory): Int = when (category) {
    DownloadCategory.All -> R.string.empty_all
    DownloadCategory.Completed -> R.string.empty_completed
    DownloadCategory.Downloading -> R.string.empty_downloading
    DownloadCategory.Waiting -> R.string.empty_waiting
    DownloadCategory.Paused -> R.string.empty_paused
    DownloadCategory.Failed -> R.string.empty_failed
}

@Composable
private fun ReportDownloadScroll(state: LazyListState) {
    val reporter by rememberUpdatedState(LocalScrollOffsetReporter.current)
    LaunchedEffect(state) {
        var previousIndex = state.firstVisibleItemIndex
        var previousOffset = state.firstVisibleItemScrollOffset
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                reporter?.invoke(index, offset,
                    index < previousIndex || (index == previousIndex && offset < previousOffset))
                previousIndex = index
                previousOffset = offset
            }
    }
}

@SuppressLint("ConfigurationScreenWidthHeight")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(navController: NavController, viewModelFactory: KunyinViewModelFactory) {
    val viewModel: DownloadViewModel = viewModel(factory = viewModelFactory)
    val allTasks by viewModel.tasks.collectAsState()
    val removingTasks by viewModel.removingTasks.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.fileRemovalFailures.collect {
            Toast.makeText(context, R.string.download_file_delete_failed, Toast.LENGTH_LONG).show()
        }
    }
    var categoryKey by rememberSaveable { mutableStateOf(DownloadCategory.All.key) }
    val category = categories.firstOrNull { it.key == categoryKey } ?: DownloadCategory.All
    val tasks = remember(allTasks, category) { allTasks.filterByCategory(category) }
    val counts = remember(allTasks) { allTasks.groupingBy { it.status }.eachCount() }
    var showClearDialog by rememberSaveable { mutableStateOf(false) }
    val categoryStates = rememberSaveableStateHolder()
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val bottomReserve = LocalFloatingBottomBarReserve.current

    LiquidGlassScene {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                GlassTopBar(title = {
                    Column {
                        Text(stringResource(R.string.download_title), fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.task_count, allTasks.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                })
            }
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (wide) {
                    LazyColumn(
                        modifier = Modifier.width(220.dp).fillMaxHeight(),
                        contentPadding = PaddingValues(start = 16.dp, end = 8.dp,
                            top = 8.dp, bottom = bottomReserve + 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(categories, key = { it.key }) { item ->
                            CategoryFilter(item, category == item,
                                categoryCount(item, counts, allTasks.size),
                                { categoryKey = item.key }, Modifier.fillMaxWidth())
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (!wide) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            categories.forEach { item ->
                                CategoryFilter(item, category == item,
                                    categoryCount(item, counts, allTasks.size),
                                    { categoryKey = item.key })
                            }
                        }
                    }
                    DownloadActions(category, counts, viewModel) { showClearDialog = true }
                    // Each filter retains its own position, also through configuration changes.
                    categoryStates.SaveableStateProvider(categoryKey) {
                        val listState = rememberLazyListState()
                        ReportDownloadScroll(listState)
                        if (tasks.isEmpty()) {
                            EmptyState(
                                message = stringResource(categoryEmptyMessage(category)),
                                icon = categoryIcon(category),
                                modifier = Modifier.fillMaxSize().padding(bottom = bottomReserve)
                            )
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp,
                                    top = 8.dp, bottom = bottomReserve + 24.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(tasks, key = { it.taskKey }, contentType = { "download" }) { task ->
                                    DownloadTaskCard(task, removing = task.taskKey in removingTasks,
                                        onPause = { viewModel.pauseTask(task.taskKey) },
                                        onResume = { viewModel.resumeTask(task.taskKey) },
                                        onRetry = { viewModel.retryTask(task.taskKey) },
                                        onCancel = { viewModel.cancelTask(task.taskKey) },
                                        onRemove = { viewModel.removeTask(task.taskKey, it) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showClearDialog) {
        GlassAlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.clear_completed_title)) },
            text = { Text(stringResource(R.string.clear_completed_confirm)) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearCompleted(); showClearDialog = false }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

private fun categoryCount(category: DownloadCategory, counts: Map<DownloadStatus, Int>, total: Int): Int =
    when (category) {
        DownloadCategory.All -> total
        DownloadCategory.Downloading -> counts[DownloadStatus.DOWNLOADING] ?: 0
        DownloadCategory.Waiting -> counts[DownloadStatus.WAITING] ?: 0
        DownloadCategory.Paused -> counts[DownloadStatus.PAUSED] ?: 0
        DownloadCategory.Completed -> counts[DownloadStatus.COMPLETED] ?: 0
        DownloadCategory.Failed -> counts[DownloadStatus.FAILED] ?: 0
    }

@Composable
private fun CategoryFilter(category: DownloadCategory, selected: Boolean, count: Int,
    onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.liquidGlass(Capsule(), selected = selected)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(categoryIcon(category), null, Modifier.size(20.dp),
            tint = if (selected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(category.titleRes), style = MaterialTheme.typography.labelLarge)
        Text(count.toString(), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DownloadActions(category: DownloadCategory, counts: Map<DownloadStatus, Int>,
    viewModel: DownloadViewModel, onClear: () -> Unit) {
    val all = category == DownloadCategory.All
    val active = (counts[DownloadStatus.DOWNLOADING] ?: 0) + (counts[DownloadStatus.WAITING] ?: 0) > 0
    val paused = (counts[DownloadStatus.PAUSED] ?: 0) > 0
    val failed = (counts[DownloadStatus.FAILED] ?: 0) > 0
    val completed = (counts[DownloadStatus.COMPLETED] ?: 0) > 0
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (active && (all || category == DownloadCategory.Downloading || category == DownloadCategory.Waiting)) {
            TextButton(onClick = viewModel::pauseAll) { Text(stringResource(R.string.pause_all)) }
        }
        if (paused && (all || category == DownloadCategory.Paused)) {
            TextButton(onClick = viewModel::resumeAll) { Text(stringResource(R.string.resume_all)) }
        }
        if (failed && (all || category == DownloadCategory.Failed)) {
            TextButton(onClick = viewModel::retryAllFailed) { Text(stringResource(R.string.retry_all_failed)) }
        }
        if (completed && (all || category == DownloadCategory.Completed)) {
            TextButton(onClick = onClear) { Text(stringResource(R.string.clear_all_completed)) }
        }
    }
}

@Composable
private fun DownloadTaskCard(task: DownloadTask, removing: Boolean, onPause: () -> Unit, onResume: () -> Unit,
    onRetry: () -> Unit, onCancel: () -> Unit, onRemove: (Boolean) -> Unit) {
    var showActions by rememberSaveable(task.taskKey) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val progress = if (task.status == DownloadStatus.COMPLETED) 1f
        else task.progress.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    Column(
        Modifier.fillMaxWidth().liquidGlass()
            .combinedClickable(enabled = !removing, onClick = { showActions = true }, onLongClick = { showActions = true })
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                    .background(colors.tertiary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Download, null, tint = colors.tertiary)
                if (task.cover.isNotBlank()) AsyncImage(task.cover, null, Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(task.title, style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOf(task.artist, task.qualityName).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            when (task.status) {
                DownloadStatus.DOWNLOADING, DownloadStatus.WAITING -> IconButton(enabled = !removing, onClick = onPause) {
                    Icon(Icons.Filled.Pause, stringResource(R.string.action_pause), tint = colors.tertiary)
                }
                DownloadStatus.PAUSED -> IconButton(enabled = !removing, onClick = onResume) {
                    Icon(Icons.Filled.PlayArrow, stringResource(R.string.action_resume), tint = colors.tertiary)
                }
                DownloadStatus.FAILED -> IconButton(enabled = !removing, onClick = onRetry) {
                    Icon(Icons.Filled.Refresh, stringResource(R.string.action_retry), tint = colors.tertiary)
                }
                DownloadStatus.COMPLETED -> if (removing) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
            IconButton(enabled = !removing, onClick = {
                if (task.status == DownloadStatus.COMPLETED || task.status == DownloadStatus.FAILED)
                    onRemove(false) else onCancel()
            }) {
                Icon(Icons.Filled.DeleteOutline, stringResource(
                    if (task.status == DownloadStatus.COMPLETED || task.status == DownloadStatus.FAILED)
                        R.string.delete_record else R.string.cancel_download),
                    tint = colors.onSurfaceVariant)
            }
        }
        if (task.status == DownloadStatus.DOWNLOADING && task.totalBytes <= 0) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp), color = colors.tertiary)
        } else {
            LinearProgressIndicator(progress = { progress },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = if (task.status == DownloadStatus.FAILED) colors.error else colors.tertiary,
                trackColor = colors.onSurface.copy(alpha = 0.08f), drawStopIndicator = {})
        }
        val statusRes = when (task.status) {
            DownloadStatus.DOWNLOADING -> R.string.cat_downloading
            DownloadStatus.WAITING -> R.string.status_waiting
            DownloadStatus.PAUSED -> R.string.status_paused
            DownloadStatus.COMPLETED -> R.string.status_completed
            DownloadStatus.FAILED -> R.string.status_failed
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(statusRes), style = MaterialTheme.typography.labelSmall,
                color = if (task.status == DownloadStatus.FAILED) colors.error else colors.tertiary)
            val detail = when (task.status) {
                DownloadStatus.DOWNLOADING -> buildString {
                    append("${(progress * 100).toInt()}% · ${formatSpeed(task.speedBytesPerSec)}")
                    append(" · ${formatDownloadBytes(task.downloadedBytes)}")
                    if (task.totalBytes > 0) append(" / ${formatDownloadBytes(task.totalBytes)}")
                }
                DownloadStatus.FAILED -> task.errorMessage
                else -> if (task.downloadedBytes > 0) formatDownloadBytes(task.downloadedBytes) else ""
            }
            Text(detail, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                color = if (task.status == DownloadStatus.FAILED) colors.error else colors.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    if (showActions) {
        TaskActionDialog(task, { showActions = false },
            { onCancel(); showActions = false }, { onRemove(it); showActions = false })
    }
}

private fun formatDownloadBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "${bytes.coerceAtLeast(0)} B"
}

@Composable
private fun TaskActionDialog(
    task: DownloadTask,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    onRemove: (deleteFile: Boolean) -> Unit
) {
    GlassAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Text(
                task.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (task.status) {
                    DownloadStatus.DOWNLOADING, DownloadStatus.WAITING, DownloadStatus.PAUSED -> {
                        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                stringResource(R.string.cancel_download),
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }

                    DownloadStatus.COMPLETED -> {
                        TextButton(
                            onClick = { onRemove(false) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                stringResource(R.string.delete_record),
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                        TextButton(
                            onClick = { onRemove(true) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                stringResource(R.string.delete_record_and_file),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    DownloadStatus.FAILED -> {
                        TextButton(
                            onClick = { onRemove(false) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                stringResource(R.string.delete_record),
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
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

private fun formatSpeed(bytesPerSec: Long): String = when {
    bytesPerSec >= 1024 * 1024 -> "%.1f MB/s".format(bytesPerSec / (1024.0 * 1024.0))
    bytesPerSec >= 1024 -> "%.1f KB/s".format(bytesPerSec / 1024.0)
    else -> "$bytesPerSec B/s"
}
