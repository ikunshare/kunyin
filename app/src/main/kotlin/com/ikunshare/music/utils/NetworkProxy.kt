package com.ikunshare.music.utils

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.WeakHashMap

internal data class ProxyConfiguration(val enabled: Boolean = false, val host: String = "127.0.0.1", val port: Int = 0)
internal enum class ProxyPhase { DISABLED, CHECKING, INVALID, UNREACHABLE, ACTIVE }
internal data class ProxyStatus(val configuration: ProxyConfiguration = ProxyConfiguration(), val phase: ProxyPhase = ProxyPhase.DISABLED)

internal class ProxyRouting(private val scope: CoroutineScope,
    private val probe: suspend (String, Int) -> Boolean = { host, port ->
        withContext(Dispatchers.IO) {
            try { Socket().use { it.connect(InetSocketAddress(host, port), 1500) }; true }
            catch (_: Exception) { false }
        }
    }) {
    private val mutableStatus = MutableStateFlow(ProxyStatus())
    val status = mutableStatus.asStateFlow()
    private var generation = 0L
    private var job: Job? = null
    private var applied: ProxyConfiguration? = null
    @Volatile private var active: Proxy = Proxy.NO_PROXY

    @Synchronized fun configure(input: ProxyConfiguration, force: Boolean = false) {
        val config = input.copy(host = input.host.trim())
        if (!force && config == applied) return
        applied = config
        val token = ++generation
        job?.cancel()
        active = Proxy.NO_PROXY
        val valid = config.host.isNotEmpty() && config.host.none { it.isWhitespace() || it == '/' } && config.port in 1..65535
        val phase = if (!config.enabled) ProxyPhase.DISABLED else if (!valid) ProxyPhase.INVALID else ProxyPhase.CHECKING
        mutableStatus.value = ProxyStatus(config, phase)
        if (phase != ProxyPhase.CHECKING) return
        job = scope.launch {
            val probing = scope.async(Dispatchers.IO) { probe(config.host, config.port) }
            val reachable = try { withTimeoutOrNull(1500) { probing.await() } ?: false }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            finally { probing.cancel() }
            coroutineContext.ensureActive()
            synchronized(this@ProxyRouting) {
                if (token == generation) {
                    active = if (reachable) Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(config.host, config.port)) else Proxy.NO_PROXY
                    mutableStatus.value = ProxyStatus(config, if (reachable) ProxyPhase.ACTIVE else ProxyPhase.UNREACHABLE)
                }
            }
        }
    }

    fun bypasses(url: HttpUrl): Boolean {
        val host = url.host.lowercase()
        return host == "localhost" || host.endsWith(".localhost") || host == "::1" ||
            host.split('.').let { parts -> parts.size == 4 && parts.first() == "127" && parts.all { it.toIntOrNull() in 0..255 } } || ('.' !in host && ':' !in host)
    }

    fun proxyFor(url: HttpUrl): Proxy = if (bypasses(url)) Proxy.NO_PROXY else active
}

internal object NetworkProxy {
    private val socketScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val routing = ProxyRouting(CoroutineScope(SupervisorJob() + Dispatchers.Default))
    private val clients = WeakHashMap<OkHttpClient, Pair<Proxy, OkHttpClient>>()
    fun client(base: OkHttpClient, url: HttpUrl): OkHttpClient = synchronized(clients) {
        val proxy = routing.proxyFor(url)
        val saved = clients[base]
        if (saved?.first == proxy) saved.second else base.newBuilder().proxy(proxy).build().also { clients[base] = proxy to it }
    }
    fun call(base: OkHttpClient, request: Request): Call = PendingProxyCall(base, request, routing) { original, saved ->
        client(original, saved.url).newCall(saved)
    }
    fun webSocket(base: OkHttpClient, request: Request, listener: WebSocketListener): WebSocket =
        PendingProxyWebSocket(request, routing, listener, socketScope) { saved, forwarding ->
            client(base, saved.url).newWebSocket(saved, forwarding)
        }
    fun factory(base: OkHttpClient): Call.Factory = Call.Factory { call(base, it) }
}
