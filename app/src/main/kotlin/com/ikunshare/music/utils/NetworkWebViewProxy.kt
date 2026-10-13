package com.ikunshare.music.utils

import android.os.Handler
import android.os.Looper
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.*
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/** Initialized only when entering a web login, avoiding WebView startup on the app's first frame. */
internal object NetworkWebViewProxy {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val executor = Executor { command -> Handler(Looper.getMainLooper()).post(command) }
    val coordinator = WebViewProxyCoordinator(NetworkProxy.routing, scope,
        supported = { WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE) },
        apply = { decision ->
            val config = webProxyConfig(decision)
            suspendCancellableCoroutine { continuation ->
                ProxyController.getInstance().setProxyOverride(config, executor) {
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
        })
}

internal fun webProxyConfig(decision: ProxyStatus): ProxyConfig {
    val builder = ProxyConfig.Builder()
    if (decision.phase == ProxyPhase.ACTIVE) {
        val host = decision.configuration.host.let { if (':' in it && !it.startsWith("[")) "[$it]" else it }
        builder.addProxyRule("http://$host:${decision.configuration.port}").bypassSimpleHostnames()
    } else builder.addDirect()
    return builder.build()
}
