package com.ikunshare.sound.sync

import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.concurrent.TimeUnit

/** 连接与同步过程的可观察状态。 */
sealed class SyncState {
    object Idle : SyncState()
    object Connecting : SyncState()
    object Syncing : SyncState()
    object Connected : SyncState()
    data class Failed(val message: String) : SyncState()
}

/**
 * LX Music WebSocket 同步客户端。负责：
 * 1. 通过 `/sync/ah` 完成 CDK 或长期 clientID 的密钥协商；
 * 2. 通过 `/sync/socket?i&t` 建立 WS；
 * 3. 解压帧并交给 [SyncRpc] 分发；发出帧前按大小决定是否 gzip；
 * 4. 每 30 秒发送 WebSocket Ping（移动协议额外发文本 "ping"）。
 */
class SyncClient(
    private val state: SyncClientState,
    private val listBridge: SyncListBridge
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow<SyncState>(SyncState.Idle)
    val status: StateFlow<SyncState> = _status.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)   // ws 长连接
        .pingInterval(SyncProtocol.HEARTBEAT_SECS, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var rpc: SyncRpc? = null
    private var heartbeatJob: Job? = null

    /** 用户希望保持连接。断线后要不要自动重连。disconnect() 把它设 false。 */
    @Volatile
    private var shouldStayConnected: Boolean = false

    /** 最近一次 connect 的参数，重连用。 */
    @Volatile
    private var lastArgs: Triple<String, String, String>? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0

    /** 建立会话：若已有 clientID 则走快速校验，否则用 CDK 激活一个新设备。 */
    fun connect(serverUrl: String, cdk: String, deviceName: String) {
        // 已经在连接 / 同步 / 已连上时，参数没变就跳过；变了再清掉重连
        val newArgs = Triple(serverUrl, cdk, deviceName)
        val st = _status.value
        val busy =
            st is SyncState.Connecting || st is SyncState.Syncing || st is SyncState.Connected
        if (busy && lastArgs == newArgs && shouldStayConnected) return

        shouldStayConnected = true
        lastArgs = newArgs
        reconnectAttempts = 0
        scope.launch {
            // 清理可能的旧连接，避免同 clientID 重复登录被服务端踢
            reconnectJob?.cancel(); reconnectJob = null
            heartbeatJob?.cancel(); heartbeatJob = null
            listBridge.unregisterHandlers()
            rpc?.destroy(); rpc = null
            webSocket?.close(SyncProtocol.CLOSE_NORMAL, "reconnect")
            webSocket = null
            connectInternal(serverUrl.trim().trimEnd('/'), cdk.trim(), deviceName)
        }
    }

    fun disconnect() {
        shouldStayConnected = false
        reconnectJob?.cancel(); reconnectJob = null
        scope.launch {
            _status.value = SyncState.Idle
            heartbeatJob?.cancel(); heartbeatJob = null
            listBridge.unregisterHandlers()
            rpc?.destroy(); rpc = null
            webSocket?.close(SyncProtocol.CLOSE_NORMAL, "bye")
            webSocket = null
        }
    }

    private fun scheduleReconnect() {
        if (!shouldStayConnected) return
        val args = lastArgs ?: return
        reconnectJob?.cancel()
        val attempt = ++reconnectAttempts
        val delayMs = when (attempt) {
            1 -> 3_000L
            2 -> 5_000L
            3 -> 10_000L
            4 -> 20_000L
            else -> 30_000L
        }
        reconnectJob = scope.launch {
            delay(delayMs)
            if (!shouldStayConnected) return@launch
            connectInternal(args.first.trim().trimEnd('/'), args.second.trim(), args.third)
        }
    }

    private fun connectInternal(baseUrl: String, cdk: String, deviceName: String) {
        _status.value = SyncState.Connecting
        _lastError.value = null
        try {
            val base = normalizeBase(baseUrl)
            // 1. hello / server id 握手（纯粹用来保证服务器版本匹配）。失败则中止。
            probeHello(base)
            // 2. 认证并获取 clientID + sessionKey
            val session = ensureSession(base, cdk, deviceName)
            state.save(session)
            // 3. 建立 WS
            openWebSocket(base, session)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            _status.value = SyncState.Failed(t.message ?: "连接失败")
            _lastError.value = t.message
            scheduleReconnect()
        }
    }

    private fun normalizeBase(url: String): String {
        return when {
            url.startsWith("http://", true) || url.startsWith("https://", true) -> url
            url.startsWith("ws://", true) -> "http://" + url.removePrefix("ws://")
            url.startsWith("wss://", true) -> "https://" + url.removePrefix("wss://")
            else -> "http://$url"
        }
    }

    private fun probeHello(base: String) {
        val req = Request.Builder().url("$base/hello").get().build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("hello 失败: HTTP ${r.code}")
            val body = r.body.string().trim()
            if (body != SyncProtocol.HELLO_MSG) {
                throw IllegalStateException("服务器不是 LX Music 同步协议 (reply=$body)")
            }
        }
    }

    private fun ensureSession(base: String, cdk: String, deviceName: String): SyncSession {
        val saved = state.load()
        if (saved != null) {
            // 快速校验：用已持久化的 key 加密 AUTH_MSG\n\n{deviceName} 发给 /sync/ah，header i 带 clientID。
            val msg = "${SyncProtocol.AUTH_MSG}\n\n$deviceName"
            val enc = SyncCrypto.aesEncrypt(msg, saved.aesKey)
            val resp = callAuth(base, enc, saved.clientId)
            if (resp != null) {
                // 成功：resp 是 aesEncrypt(HelloMsg, key)
                val hello = runCatching { SyncCrypto.aesDecrypt(resp, saved.aesKey) }.getOrNull()
                if (hello == SyncProtocol.HELLO_MSG) return saved
            }
            // 失败则回落到 CDK 重建
        }

        // CDK 流程：本地生成 RSA keypair，把 publicKey 发给服务端，服务端 RSA 加密回 clientID+key
        val kp = generateRsa()
        val publicKeyPem = Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP)
        val body = buildString {
            append(SyncProtocol.AUTH_MSG)
            append('\n'); append(publicKeyPem)
            append('\n'); append(deviceName)
            append('\n'); append(SyncProtocol.CLIENT_KIND_MOBILE)
        }
        val aesKey = SyncCrypto.deriveAESKey(cdk)
        val enc = SyncCrypto.aesEncrypt(body, aesKey)
        val resp = callAuth(base, enc, null) ?: throw IllegalStateException("CDK 激活失败")
        val decrypted = SyncCrypto.rsaDecrypt(resp, kp.private)
        val json = String(decrypted, Charsets.UTF_8)
        val info = parseAuthResponse(json)
        return SyncSession(info.clientId, info.sessionKey, info.serverName)
    }

    private fun callAuth(base: String, encMsg: String, clientId: String?): String? {
        val req = Request.Builder()
            .url("$base/ah")
            .get()
            .apply {
                addHeader("m", encMsg)
                if (!clientId.isNullOrBlank()) addHeader("i", clientId)
            }
            .build()
        val r: Response = http.newCall(req).execute()
        r.use {
            if (!it.isSuccessful) return null
            return it.body.string()
        }
    }

    private data class AuthPayload(
        val clientId: String,
        val sessionKey: String,
        val serverName: String
    )

    private fun parseAuthResponse(json: String): AuthPayload {
        val o = com.google.gson.JsonParser.parseString(json).asJsonObject
        return AuthPayload(
            clientId = o.get("clientId").asString,
            sessionKey = o.get("key").asString,
            serverName = o.get("serverName")?.takeIf { !it.isJsonNull }?.asString ?: ""
        )
    }

    private fun generateRsa(): KeyPair {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        return gen.generateKeyPair()
    }

    private fun openWebSocket(base: String, session: SyncSession) {
        val wsBase = base.replaceFirst(Regex("^http", RegexOption.IGNORE_CASE), "ws")
        val token = SyncCrypto.aesEncrypt(SyncProtocol.MSG_CONNECT, session.aesKey)
        val encodedToken = java.net.URLEncoder.encode(token, "UTF-8")
        val url = "$wsBase/socket?i=${session.clientId}&t=$encodedToken"

        val req = Request.Builder().url(url).build()
        _status.value = SyncState.Syncing
        webSocket = http.newWebSocket(req, listener)
    }

    private fun sendFrame(text: String): Boolean {
        val ws = webSocket ?: return false
        val payload = SyncCrypto.compressMsg(text)
        return ws.send(payload)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            // 仅响应当前活跃 socket。被 connect() 替换的旧 socket 回调全部丢弃。
            if (webSocket !== this@SyncClient.webSocket) {
                webSocket.cancel(); return
            }
            reconnectAttempts = 0
            val localRpc = SyncRpc(send = { sendFrame(it) }, onError = { })
            listBridge.registerHandlers(localRpc)
            rpc = localRpc
            // 每 HEARTBEAT_SECS 秒发一次纯文本 "ping"。配合服务端读循环的 ping/pong 分支保活；
            // 传输层 WebSocket Ping 控制帧由 OkHttp pingInterval 自动发送。
            heartbeatJob?.cancel()
            heartbeatJob = scope.launch {
                while (true) {
                    delay(SyncProtocol.HEARTBEAT_SECS * 1000)
                    val ws = this@SyncClient.webSocket ?: break
                    if (ws !== webSocket) break
                    if (!ws.send("ping")) break
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== this@SyncClient.webSocket) return
            val plain = try {
                SyncCrypto.decompressMsg(text)
            } catch (e: Exception) {
                _lastError.value = "decompress text failed: ${e.message}"
                return
            }
            if (plain == "ping" || plain == "pong") return
            rpc?.handleIncoming(plain)
            if (_status.value == SyncState.Syncing) _status.value = SyncState.Connected
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (webSocket !== this@SyncClient.webSocket) return
            val raw = bytes.toByteArray()
            val text = try {
                when {
                    SyncCrypto.isGzipBytes(raw) -> SyncCrypto.decompressGzipBytes(raw)
                    else -> bytes.utf8()
                }
            } catch (e: Exception) {
                _lastError.value = "decompress bytes failed: ${e.message}"
                return
            }
            onMessage(webSocket, text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            // 不是当前 socket 的回调（被替换的旧连接），不动状态、不重连
            if (webSocket !== this@SyncClient.webSocket) return
            heartbeatJob?.cancel()
            listBridge.unregisterHandlers()
            rpc?.destroy()
            rpc = null
            this@SyncClient.webSocket = null
            if (_status.value !is SyncState.Failed) _status.value = SyncState.Idle
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket !== this@SyncClient.webSocket) return
            heartbeatJob?.cancel()
            listBridge.unregisterHandlers()
            rpc?.destroy()
            rpc = null
            this@SyncClient.webSocket = null
            _status.value = SyncState.Failed(t.message ?: "连接异常")
            _lastError.value = t.message
            scheduleReconnect()
        }
    }
}
