package com.ikunshare.music.ui.screens.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikunshare.music.manager.DownloadManager
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

class DownloadViewModel(
    private val downloadManager: DownloadManager
) : ViewModel() {

    val tasks: StateFlow<List<DownloadTask>> = downloadManager.tasks
    private val _removingTasks = MutableStateFlow<Set<String>>(emptySet())
    val removingTasks = _removingTasks.asStateFlow()
    private val removalFailures = Channel<Unit>(Channel.BUFFERED)
    val fileRemovalFailures = removalFailures.receiveAsFlow()

    fun retryTask(taskKey: String) = downloadManager.retryTask(taskKey)

    fun cancelTask(taskKey: String) = downloadManager.cancelTask(taskKey)

    fun removeTask(taskKey: String, deleteFile: Boolean = false) {
        if (taskKey in _removingTasks.value) return
        _removingTasks.update { it + taskKey }
        viewModelScope.launch {
            try {
                if (!downloadManager.removeTask(taskKey, deleteFile)) removalFailures.trySend(Unit)
            } finally { _removingTasks.update { it - taskKey } }
        }
    }

    fun pauseTask(taskKey: String) = downloadManager.pauseTask(taskKey)
    fun resumeTask(taskKey: String) = downloadManager.resumeTask(taskKey)
    fun pauseAll() = downloadManager.pauseAll()
    fun resumeAll() = downloadManager.resumeAll()
    fun retryAllFailed() = downloadManager.retryAllFailed()
    fun clearCompleted() = downloadManager.clearCompleted()
}

/** 从任务列表中按分类过滤 */
fun List<DownloadTask>.filterByCategory(category: DownloadCategory): List<DownloadTask> {
    return when (category) {
        DownloadCategory.All -> this
        DownloadCategory.Completed -> filter { it.status == DownloadStatus.COMPLETED }
        DownloadCategory.Downloading -> filter { it.status == DownloadStatus.DOWNLOADING }
        DownloadCategory.Waiting -> filter { it.status == DownloadStatus.WAITING }
        DownloadCategory.Failed -> filter { it.status == DownloadStatus.FAILED }
        DownloadCategory.Paused -> filter { it.status == DownloadStatus.PAUSED }
    }
}
