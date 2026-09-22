package com.ikunshare.sound.sync

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

typealias RpcRequestHandler = suspend (List<JsonElement>) -> Any?

/**
 * 与 Go 端 `sync/rpc.go` 双向兼容的请求/响应协议。
 * - 请求帧带 `path`，响应帧只有 `name`。
 * - Go 端 `Error` 字段没有 omitempty，故我们的响应始终写入 `"error":null` / `"error":"..."`。
 * - 序列化后调 [send]，对端已解压并解码的 JSON 文本通过 [handleIncoming] 输入。
 */
class SyncRpc(
    private val send: (String) -> Boolean,
    private val onError: (Throwable) -> Unit
) {
    private val gson = Gson()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()
    private val handlers = ConcurrentHashMap<String, RpcRequestHandler>()
    private val seq = AtomicLong(System.currentTimeMillis())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun register(method: String, handler: RpcRequestHandler) {
        handlers[method] = handler
    }

    fun destroy() {
        pending.values.forEach { it.completeExceptionally(IllegalStateException("rpc destroyed")) }
        pending.clear()
        scope.cancel()
    }

    /** 发起一次远程调用并等待对端回复；args 以 JSON 数组形式打包。 */
    suspend fun call(method: String, vararg args: Any?): JsonElement {
        val name = "${method}__${seq.incrementAndGet()}"
        val frame = JsonObject().apply {
            addProperty("name", name)
            val pathArr = JsonArray().apply { method.split('.').forEach { add(it) } }
            add("path", pathArr)
            val argsArr = JsonArray().apply {
                args.forEach { v ->
                    add(if (v == null) JsonNull.INSTANCE else gson.toJsonTree(v))
                }
            }
            add("data", argsArr)
        }
        val pendingCall = CompletableDeferred<JsonElement>()
        pending[name] = pendingCall
        try {
            if (!send(gson.toJson(frame))) throw IllegalStateException("rpc send failed: socket closed")
            return withTimeout(SyncProtocol.RPC_TIMEOUT_MS) { pendingCall.await() }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException("rpc timeout: $method", e)
        } finally {
            pending.remove(name)
        }
    }

    /** 对端发来的一帧已解压 JSON。 */
    fun handleIncoming(text: String) {
        val obj = try {
            JsonParser.parseString(text).asJsonObject
        } catch (_: Exception) {
            return
        }
        val name = obj.get("name")?.takeIf { !it.isJsonNull }?.asString ?: return
        val pathEl = obj.get("path")
        if (pathEl != null && !pathEl.isJsonNull && pathEl.isJsonArray && pathEl.asJsonArray.size() > 0) {
            val method = pathEl.asJsonArray.joinToString(".") { it.asString }
            val handler = handlers[method]
            if (handler == null) {
                replyError(name, "$method is not defined")
                return
            }
            scope.launch {
                try {
                    val data = obj.get("data")
                    val args: List<JsonElement> = when {
                        data == null || data.isJsonNull -> emptyList()
                        data.isJsonArray -> data.asJsonArray.toList()
                        else -> listOf(data)
                    }
                    val result = handler(args)
                    replyOk(name, result)
                } catch (t: Throwable) {
                    replyError(name, t.message ?: "handler error")
                }
            }
            return
        }
        val deferred = pending.remove(name) ?: return
        val err = obj.get("error")?.takeIf { !it.isJsonNull }?.asString
        if (err != null) {
            deferred.completeExceptionally(IllegalStateException("rpc error: $err"))
        } else {
            deferred.complete(obj.get("data") ?: JsonNull.INSTANCE)
        }
    }

    private fun replyOk(name: String, result: Any?) {
        val frame = JsonObject().apply {
            addProperty("name", name)
            add("error", JsonNull.INSTANCE)
            add("data", if (result == null) JsonNull.INSTANCE else gson.toJsonTree(result))
        }
        if (!send(gson.toJson(frame))) onError(IllegalStateException("rpc reply failed"))
    }

    private fun replyError(name: String, err: String) {
        val frame = JsonObject().apply {
            addProperty("name", name)
            addProperty("error", err)
        }
        send(gson.toJson(frame))
    }
}
