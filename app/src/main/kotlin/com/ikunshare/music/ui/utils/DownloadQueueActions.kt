package com.ikunshare.music.ui.utils

import com.ikunshare.music.R
import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.ikunshare.music.manager.DownloadManager
import com.ikunshare.music.model.MusicItem
import com.ikunshare.music.ui.screens.download.DownloadDisc
import kotlinx.coroutines.*

internal data class DownloadQueueRequest(val song: MusicItem, val quality: String, val hideAi: Boolean,
    val subDir: String = "", val trackNumber: Int = 0, val disc: DownloadDisc? = null, val exactQuality: Boolean = false)

internal fun enqueueDownloadRequest(manager: DownloadManager, request: DownloadQueueRequest): Boolean =
    if (request.exactQuality) manager.addTask(request.song, request.quality,
        deferSchedule = true, subDir = request.subDir, trackNumber = request.trackNumber, disc = request.disc)
    else manager.addTaskWithPreferredQuality(request.song, request.quality, request.hideAi,
        deferSchedule = true, subDir = request.subDir, trackNumber = request.trackNumber, disc = request.disc)

internal suspend fun enqueueDownloadRequests(requests: List<DownloadQueueRequest>,
    enqueue: (DownloadQueueRequest) -> Boolean, flush: () -> Unit): Int {
    val snapshot = requests.toList()
    return withContext(Dispatchers.IO) {
        var added = 0
        try {
            for (request in snapshot) {
                currentCoroutineContext().ensureActive()
                if (enqueue(request)) added++
            }
        } finally { flush() }
        added
    }
}

@Composable
internal fun rememberDownloadQueueActions(manager: DownloadManager, ownerKey: Any? = null): DownloadQueueActions = key(ownerKey) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    remember(manager, context, scope) {
        DownloadQueueActions(context, scope, { request ->
            enqueueDownloadRequest(manager, request)
        }, manager::flushSchedule)
    }
}

internal class DownloadQueueActions(private val context: Context, private val scope: CoroutineScope,
    private val enqueue: (DownloadQueueRequest) -> Boolean, private val flush: () -> Unit,
    private val feedback: (String) -> Unit = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }) {
    var isEnqueuing by mutableStateOf(false)
        private set

    fun add(songs: List<MusicItem>, quality: String, hideAi: Boolean, onFinished: () -> Unit) =
        addRequests(songs.map { DownloadQueueRequest(it, quality, hideAi) }, onFinished)

    fun addRequests(requests: List<DownloadQueueRequest>, onFinished: () -> Unit) {
        if (isEnqueuing) return
        val snapshot = requests.toList()
        isEnqueuing = true
        scope.launch {
            try {
                val added = enqueueDownloadRequests(snapshot, enqueue, flush)
                onFinished()
                feedback(context.getString(if (added > 0) R.string.added_to_download_queue_count
                    else R.string.download_not_added, added))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                feedback(context.getString(R.string.download_failed_msg, failure.message.orEmpty()))
            }
        }.invokeOnCompletion { isEnqueuing = false }
    }
}
