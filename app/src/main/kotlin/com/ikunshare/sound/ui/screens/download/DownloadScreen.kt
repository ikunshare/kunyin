package com.ikunshare.sound.ui.screens.download

import android.annotation.SuppressLint
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.ikunshare.sound.R
import com.ikunshare.sound.ui.KunyinViewModelFactory
import com.ikunshare.sound.ui.components.EmptyState
import com.ikunshare.sound.ui.theme.LocalUiColors
import com.ikunshare.sound.ui.utils.LocalScrollOffsetReporter

// ===== 布局常量 =====
/** 列表底部占位高度，为底栏 + MiniPlayer 预留空间，避免最后一项被遮挡 */
private val ListBottomSpacing = 80.dp

/** 列表顶部微调间距 */
private val ListTopSpacing = 4.dp

/** 宽屏布局整体顶部偏移（避开状态栏区域） */
private val WideLayoutTopPadding = 32.dp

/** 宽屏左侧分类列表宽度 */
private val CategoryPaneWidth = 300.dp

private val categories = listOf(
    DownloadCategory.All,
    DownloadCategory.Downloading,
    DownloadCategory.Waiting,
    DownloadCategory.Paused,
    DownloadCategory.Completed,
    DownloadCategory.Failed
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
private fun RememberScrollOffsetReporter(
    lazyListState: androidx.compose.foundation.lazy.LazyListState
) {
    // 监听滚动偏移量并上报
    val scrollOffsetReporter = LocalScrollOffsetReporter.current

    var previousVisibleItemIndex by remember { mutableIntStateOf(0) }
    var previousScrollOffset by remember { mutableIntStateOf(0) }

    LaunchedEffect(lazyListState) {
        snapshotFlow {
            lazyListState.firstVisibleItemIndex to lazyListState.firstVisibleItemScrollOffset
        }.collect { (firstVisibleItemIndex, scrollOffset) ->
            val isScrollingUp = firstVisibleItemIndex < previousVisibleItemIndex ||
                    (firstVisibleItemIndex == previousVisibleItemIndex && scrollOffset < previousScrollOffset)
            scrollOffsetReporter?.invoke(firstVisibleItemIndex, scrollOffset, isScrollingUp)
            previousVisibleItemIndex = firstVisibleItemIndex
            previousScrollOffset = scrollOffset
        }
    }
}

@SuppressLint("ConfigurationScreenWidthHeight")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(
    navController: NavController,
    viewModelFactory: KunyinViewModelFactory
) {
    val viewModel: DownloadViewModel = viewModel(factory = viewModelFactory)
    val allTasks by viewModel.tasks.collectAsState()

    val configuration = LocalConfiguration.current
    val isWideLayout =
        configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                || configuration.screenWidthDp >= 600

    if (isWideLayout) {
        WideDownloadLayout(
            allTasks = allTasks,
            viewModel = viewModel
        )
    } else {
        NarrowDownloadLayout(
            allTasks = allTasks,
            viewModel = viewModel
        )
    }
}

// ===== Wide layout =====

@Composable
private fun WideDownloadLayout(
    allTasks: List<DownloadTask>,
    viewModel: DownloadViewModel
) {
    var selectedCategory by remember { mutableStateOf<DownloadCategory>(DownloadCategory.All) }
    val tasks = allTasks.filterByCategory(selectedCategory)
    var showClearDialog by remember { mutableStateOf(false) }

    // 左侧分类列表的滚动状态
    val categoryListState = rememberLazyListState()
    RememberScrollOffsetReporter(categoryListState)
    // 右侧任务列表的滚动状态
    val taskListState = rememberLazyListState()
    RememberScrollOffsetReporter(taskListState)

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = WideLayoutTopPadding)
    ) {
        LazyColumn(
            state = categoryListState,
            modifier = Modifier
                .width(CategoryPaneWidth)
                .fillMaxHeight()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(categories, key = { it.key }) { category ->  // 使用 items
                CategoryCard(
                    title = stringResource(category.titleRes),
                    count = allTasks.filterByCategory(category).size,
                    icon = categoryIcon(category),
                    compact = true,
                    selected = selectedCategory.key == category.key,
                    onClick = { selectedCategory = category }
                )
            }
        }

        VerticalDivider()

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            // 分类操作按钮
            CategoryActionBar(
                category = selectedCategory,
                allTasks = allTasks,
                onPauseAll = viewModel::pauseAll,
                onResumeAll = viewModel::resumeAll,
                onClearCompleted = { showClearDialog = true },
                onRetryAllFailed = viewModel::retryAllFailed
            )

            if (tasks.isEmpty()) {
                EmptyState(
                    message = stringResource(categoryEmptyMessage(selectedCategory)),
                    icon = categoryIcon(selectedCategory),
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                LazyColumn(
                    state = taskListState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(
                        items = tasks,
                        key = { "${it.taskKey}_${selectedCategory.key}" }) { task ->
                        DownloadTaskCard(
                            task = task,
                            onRetry = if (task.status == DownloadStatus.FAILED) {
                                { viewModel.retryTask(task.taskKey) }
                            } else null,
                            onPause = if (task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.WAITING) {
                                { viewModel.pauseTask(task.taskKey) }
                            } else null,
                            onResume = if (task.status == DownloadStatus.PAUSED) {
                                { viewModel.resumeTask(task.taskKey) }
                            } else null,
                            onCancel = { viewModel.cancelTask(task.taskKey) },
                            onRemove = { deleteFile ->
                                viewModel.removeTask(
                                    task.taskKey,
                                    deleteFile
                                )
                            }
                        )
                    }
                    item(key = "bottom_spacer") { Spacer(Modifier.height(ListBottomSpacing)) }
                }
            }
        }
    }

    if (showClearDialog) {
        ClearCompletedDialog(
            onDismiss = { showClearDialog = false },
            onConfirm = { viewModel.clearCompleted(); showClearDialog = false }
        )
    }
}

// ===== Narrow layout =====

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NarrowDownloadLayout(
    allTasks: List<DownloadTask>,
    viewModel: DownloadViewModel
) {
    var selectedCategory by remember { mutableStateOf<DownloadCategory?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }

    // 滚动状态监听
    val downloadListState = rememberLazyListState()
    RememberScrollOffsetReporter(downloadListState)
    val detailListState = rememberLazyListState()
    RememberScrollOffsetReporter(detailListState)

    BackHandler(enabled = selectedCategory != null) {
        selectedCategory = null
    }

    AnimatedContent(
        targetState = selectedCategory,
        transitionSpec = {
            if (targetState != null) {
                slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
            } else {
                slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
            }
        },
        label = "download_nav"
    ) { category ->
        if (category == null) {
            // 分类列表主页
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                stringResource(R.string.download_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                },
                containerColor = Color.Transparent
            ) { padding ->
                LazyColumn(
                    state = downloadListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item(key = "top_spacer") { Spacer(Modifier.height(ListTopSpacing)) }

                    items(items = categories, key = { it.key }) { cat ->
                        CategoryCard(
                            title = stringResource(cat.titleRes),
                            count = allTasks.filterByCategory(cat).size,
                            icon = categoryIcon(cat),
                            compact = false,
                            onClick = { selectedCategory = cat }
                        )
                    }

                    item(key = "bottom_spacer") { Spacer(Modifier.height(ListBottomSpacing)) }
                }
            }
        } else {
            // 分类详情页
            val tasks = allTasks.filterByCategory(category)

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                stringResource(category.titleRes),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = { selectedCategory = null }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.back),
                                    tint = MaterialTheme.colorScheme.onBackground
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                },
                containerColor = Color.Transparent
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    // 分类操作按钮
                    CategoryActionBar(
                        category = category,
                        allTasks = allTasks,
                        onPauseAll = viewModel::pauseAll,
                        onResumeAll = viewModel::resumeAll,
                        onClearCompleted = { showClearDialog = true },
                        onRetryAllFailed = viewModel::retryAllFailed
                    )

                    if (tasks.isEmpty()) {
                        EmptyState(
                            message = stringResource(categoryEmptyMessage(category)),
                            icon = categoryIcon(category),
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        LazyColumn(
                            state = detailListState,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(
                                items = tasks,
                                key = { "task_${it.taskKey}" }
                            ) { task ->
                                DownloadTaskCard(
                                    task = task,
                                    onRetry = if (task.status == DownloadStatus.FAILED) {
                                        { viewModel.retryTask(task.taskKey) }
                                    } else null,
                                    onPause = if (task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.WAITING) {
                                        { viewModel.pauseTask(task.taskKey) }
                                    } else null,
                                    onResume = if (task.status == DownloadStatus.PAUSED) {
                                        { viewModel.resumeTask(task.taskKey) }
                                    } else null,
                                    onCancel = { viewModel.cancelTask(task.taskKey) },
                                    onRemove = { deleteFile ->
                                        viewModel.removeTask(
                                            task.taskKey,
                                            deleteFile
                                        )
                                    }
                                )
                            }
                            item(key = "bottom_spacer") { Spacer(Modifier.height(ListBottomSpacing)) }
                        }
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        ClearCompletedDialog(
            onDismiss = { showClearDialog = false },
            onConfirm = { viewModel.clearCompleted(); showClearDialog = false }
        )
    }
}

// ===== 分类操作按钮栏 =====

@Composable
private fun CategoryActionBar(
    category: DownloadCategory,
    allTasks: List<DownloadTask>,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onClearCompleted: () -> Unit,
    onRetryAllFailed: () -> Unit
) {
    val hasActive =
        allTasks.any { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.WAITING }
    val hasPaused = allTasks.any { it.status == DownloadStatus.PAUSED }
    val hasCompleted = allTasks.any { it.status == DownloadStatus.COMPLETED }
    val hasFailed = allTasks.any { it.status == DownloadStatus.FAILED }

    val showButton = when (category) {
        DownloadCategory.Downloading, DownloadCategory.Waiting -> hasActive
        DownloadCategory.Paused -> true
        DownloadCategory.Completed -> true
        DownloadCategory.Failed -> true
        else -> false
    }

    if (!showButton) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End
    ) {
        when (category) {
            DownloadCategory.Downloading, DownloadCategory.Waiting -> {
                TextButton(onClick = onPauseAll, enabled = hasActive) {
                    Text(
                        stringResource(R.string.pause_all),
                        color = if (hasActive) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            DownloadCategory.Paused -> {
                TextButton(onClick = onResumeAll, enabled = hasPaused) {
                    Text(
                        stringResource(R.string.resume_all),
                        color = if (hasPaused) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            DownloadCategory.Completed -> {
                TextButton(onClick = onClearCompleted, enabled = hasCompleted) {
                    Text(
                        stringResource(R.string.clear_all_completed),
                        color = if (hasCompleted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            DownloadCategory.Failed -> {
                TextButton(onClick = onRetryAllFailed, enabled = hasFailed) {
                    Text(
                        stringResource(R.string.retry_all_failed),
                        color = if (hasFailed) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            else -> {}
        }
    }
}

// ===== 清除已完成确认对话框 =====

@Composable
private fun ClearCompletedDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.clear_completed_title)) },
        text = { Text(stringResource(R.string.clear_completed_confirm)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.confirm), color = MaterialTheme.colorScheme.error)
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

// ===== Shared components =====

/**
 * 分类卡片。[compact] 为宽屏左栏使用的紧凑样式（更小图标/字号、支持选中态），
 * 否则为窄屏主页的常规样式。
 */
@Composable
private fun CategoryCard(
    title: String,
    count: Int,
    icon: ImageVector,
    onClick: () -> Unit,
    compact: Boolean = false,
    selected: Boolean = false
) {
    val containerColor = if (compact && selected) MaterialTheme.colorScheme.secondaryContainer
    else MaterialTheme.colorScheme.surfaceVariant
    val rowPadding = if (compact) 12.dp else 16.dp
    val iconBoxSize = if (compact) 48.dp else 56.dp
    val iconBoxRadius = if (compact) 6.dp else 8.dp
    val iconSize = if (compact) 24.dp else 28.dp
    val spacing = if (compact) 12.dp else 16.dp
    val titleStyle = if (compact) MaterialTheme.typography.bodyLarge
    else MaterialTheme.typography.titleMedium

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = containerColor,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(rowPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(iconBoxSize)
                    .clip(RoundedCornerShape(iconBoxRadius)),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(iconBoxRadius)
                ) {}
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(iconSize),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(spacing))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = titleStyle.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!compact) Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.task_count, count),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadTaskCard(
    task: DownloadTask,
    onRetry: (() -> Unit)? = null,
    onPause: (() -> Unit)? = null,
    onResume: (() -> Unit)? = null,
    onCancel: () -> Unit = {},
    onRemove: (deleteFile: Boolean) -> Unit = {}
) {
    var showActionDialog by remember { mutableStateOf(false) }
    val cardColor = LocalUiColors.current.songCardColor

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .combinedClickable(onClick = {}, onLongClick = { showActionDialog = true }),
        color = cardColor,
        shape = RoundedCornerShape(12.dp),
        shadowElevation = if (cardColor.alpha < 1f) 0.dp else 2.dp
    ) {
        Column {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = task.cover, contentDescription = task.title,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        task.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            task.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (task.qualityName.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer
                            ) {
                                Text(
                                    task.qualityName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))

                // Status + action
                when (task.status) {
                    DownloadStatus.DOWNLOADING -> {
                        val percent = (task.progress * 100).toInt()
                        val speed = formatSpeed(task.speedBytesPerSec)
                        Text(
                            "$percent% · $speed",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (onPause != null) {
                            IconButton(onClick = onPause, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    Icons.Filled.Pause,
                                    contentDescription = stringResource(R.string.action_pause),
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    DownloadStatus.WAITING -> {
                        Text(
                            stringResource(R.string.status_waiting),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (onPause != null) {
                            IconButton(onClick = onPause, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    Icons.Filled.Pause,
                                    contentDescription = stringResource(R.string.action_pause),
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    DownloadStatus.PAUSED -> {
                        Text(
                            stringResource(R.string.status_paused),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (onResume != null) {
                            IconButton(onClick = onResume, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = stringResource(R.string.action_resume),
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        }
                    }

                    DownloadStatus.COMPLETED -> {
                        Text(
                            stringResource(R.string.status_completed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }

                    DownloadStatus.FAILED -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                task.errorMessage.ifEmpty { stringResource(R.string.status_failed) },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (onRetry != null) {
                                IconButton(onClick = onRetry, modifier = Modifier.size(32.dp)) {
                                    Icon(
                                        Icons.Filled.Refresh,
                                        contentDescription = stringResource(R.string.action_retry),
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.tertiary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (task.status == DownloadStatus.DOWNLOADING) {
                LinearProgressIndicator(
                    progress = { task.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        }
    }

    if (showActionDialog) {
        TaskActionDialog(
            task = task,
            onDismiss = { showActionDialog = false },
            onCancel = { onCancel(); showActionDialog = false },
            onRemove = { deleteFile -> onRemove(deleteFile); showActionDialog = false }
        )
    }
}

@Composable
private fun TaskActionDialog(
    task: DownloadTask,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    onRemove: (deleteFile: Boolean) -> Unit
) {
    AlertDialog(
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
