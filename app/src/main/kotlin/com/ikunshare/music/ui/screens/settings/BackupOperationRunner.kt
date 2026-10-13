package com.ikunshare.music.ui.screens.settings

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Busy is acquired on submission, before any worker can start; it includes result delivery. */
internal class BackupOperationRunner(
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher = Dispatchers.IO,
    private val callbacks: CoroutineDispatcher = Dispatchers.Main,
) {
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()

    fun <T> submit(operation: suspend () -> T, onResult: (Result<T>) -> Unit): Boolean {
        if (!mutableBusy.compareAndSet(false, true)) return false
        val job = try {
            scope.launch {
                val result = withContext(worker) {
                    try { Result.success(operation()) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { Result.failure(failure) }
                }
                withContext(callbacks) { onResult(result) }
            }
        } catch (failure: Throwable) {
            mutableBusy.value = false
            throw failure
        }
        job.invokeOnCompletion { mutableBusy.value = false }
        return true
    }
}
