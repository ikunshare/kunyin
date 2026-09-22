package com.ikunshare.sound.ui.screens.download

import androidx.lifecycle.ViewModel
import com.ikunshare.sound.manager.DownloadManager
import kotlinx.coroutines.flow.StateFlow

class DownloadViewModel(
    private val downloadManager: DownloadManager
) : ViewModel() {

    val tasks: StateFlow<List<DownloadTask>> = downloadManager.tasks

    fun retryTask(taskKey: String) = downloadManager.retryTask(taskKey)

    fun cancelTask(taskKey: String) = downloadManager.cancelTask(taskKey)

    fun removeTask(taskKey: String, deleteFile: Boolean = false) =
        downloadManager.removeTask(taskKey, deleteFile)

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
