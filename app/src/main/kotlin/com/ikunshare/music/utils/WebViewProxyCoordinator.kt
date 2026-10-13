package com.ikunshare.music.utils

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal enum class WebProxyPhase { WAITING, APPLYING, READY, UNSUPPORTED, FAILED }
internal data class WebProxyStatus(val phase: WebProxyPhase = WebProxyPhase.WAITING, val decision: ProxyStatus? = null) {
    fun allowsLoad(current: ProxyStatus): Boolean = current.phase != ProxyPhase.CHECKING && decision == current &&
        (phase == WebProxyPhase.READY || (phase == WebProxyPhase.UNSUPPORTED && current.phase != ProxyPhase.ACTIVE))
}

/** Serializes process-wide overrides: cancelling an await does not undo a WebView override. */
internal class WebViewProxyCoordinator(
    routing: ProxyRouting,
    scope: CoroutineScope,
    private val supported: () -> Boolean,
    private val apply: suspend (ProxyStatus) -> Unit,
) {
    private val mutableStatus = MutableStateFlow(WebProxyStatus())
    val status = mutableStatus.asStateFlow()
    private val retryRevision = MutableStateFlow(0L)
    init {
        scope.launch {
            combine(routing.status, retryRevision) { decision, _ -> decision }.collect { decision ->
                if (decision.phase == ProxyPhase.CHECKING) {
                    mutableStatus.value = WebProxyStatus(WebProxyPhase.WAITING, decision)
                    return@collect
                }
                mutableStatus.value = WebProxyStatus(WebProxyPhase.APPLYING, decision)
                try {
                    if (!supported()) {
                        mutableStatus.value = WebProxyStatus(WebProxyPhase.UNSUPPORTED, decision)
                        return@collect
                    }
                    apply(decision)
                    // A completed older override cannot unlock a page for newer settings.
                    mutableStatus.value = if (routing.status.value == decision)
                        WebProxyStatus(WebProxyPhase.READY, decision) else WebProxyStatus()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { mutableStatus.value = WebProxyStatus(WebProxyPhase.FAILED, decision) }
            }
        }
    }
    fun retry() { retryRevision.update { it + 1 } }
}
