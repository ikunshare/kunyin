package com.ikunshare.music.utils

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import java.io.IOException

/** Keeps the caller's socket and frame order stable while the proxy decision is pending. */
internal class PendingProxyWebSocket(
    private val original: Request,
    private val routing: ProxyRouting,
    private val listener: WebSocketListener,
    scope: CoroutineScope,
    private val create: (Request, WebSocketListener) -> WebSocket,
) : WebSocket {
    private sealed interface Frame {
        data class Text(val value: String) : Frame
        data class Binary(val value: ByteString) : Frame
        data class Close(val code: Int, val reason: String?) : Frame
    }
    private val lock = Any()
    private val frames = ArrayDeque<Frame>()
    private var queuedBytes = 0L
    private var delegate: WebSocket? = null
    private var cancelled = false
    private var terminal = false
    private var closing = false
    private val connect = scope.launch(start = CoroutineStart.LAZY) {
        routing.status.first { it.phase != ProxyPhase.CHECKING || routing.bypasses(original.url) }
        ensureActive()
        if (synchronized(lock) { cancelled }) return@launch
        try { attach(create(original, forwarding)) }
        catch (e: Exception) { fail(e, null) }
    }

    private val forwarding = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            attach(webSocket)
            if (synchronized(lock) { !terminal && !cancelled }) listener.onOpen(this@PendingProxyWebSocket, response)
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (synchronized(lock) { !terminal }) listener.onMessage(this@PendingProxyWebSocket, text)
        }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (synchronized(lock) { !terminal }) listener.onMessage(this@PendingProxyWebSocket, bytes)
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (synchronized(lock) { !terminal }) listener.onClosing(this@PendingProxyWebSocket, code, reason)
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (finish()) listener.onClosed(this@PendingProxyWebSocket, code, reason)
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = fail(t, response)
    }

    init { connect.start() }

    private fun attach(socket: WebSocket) = synchronized(lock) {
        if (delegate != null) return@synchronized
        delegate = socket
        if (cancelled || terminal) { socket.cancel(); return@synchronized }
        for (frame in frames) when (frame) {
            is Frame.Text -> socket.send(frame.value)
            is Frame.Binary -> socket.send(frame.value)
            is Frame.Close -> socket.close(frame.code, frame.reason)
        }
        frames.clear()
        queuedBytes = 0
    }

    private fun finish(): Boolean = synchronized(lock) {
        if (terminal) false else { terminal = true; frames.clear(); queuedBytes = 0; true }
    }
    private fun fail(t: Throwable, response: Response?) {
        if (finish()) listener.onFailure(this, t, response)
    }
    override fun request(): Request = original
    override fun queueSize(): Long = synchronized(lock) { delegate?.queueSize() ?: queuedBytes }
    override fun send(text: String): Boolean = send(Frame.Text(text), text.encodeUtf8().size)
    override fun send(bytes: ByteString): Boolean = send(Frame.Binary(bytes), bytes.size)
    private fun send(frame: Frame, size: Int): Boolean = synchronized(lock) {
        if (terminal || cancelled || closing) return false
        delegate?.let { return when (frame) {
            is Frame.Text -> it.send(frame.value)
            is Frame.Binary -> it.send(frame.value)
            is Frame.Close -> false
        } }
        if (queuedBytes + size > 16L * 1024 * 1024) { close(1001, null); return false }
        frames.addLast(frame)
        queuedBytes += size
        true
    }
    override fun close(code: Int, reason: String?): Boolean {
        require(code in 1000..4999) { "Code must be in range [1000,5000): $code" }
        require(code !in 1004..1006 && code !in 1015..2999) { "Code $code is reserved and may not be used." }
        require(reason == null || reason.encodeUtf8().size <= 123) { "reason.size() > 123: $reason" }
        return synchronized(lock) {
            if (terminal || cancelled || closing) return false
            closing = true
            delegate?.close(code, reason) ?: run { frames.addLast(Frame.Close(code, reason)); true }
        }
    }
    override fun cancel() {
        val socket = synchronized(lock) { cancelled = true; delegate }
        if (socket != null) socket.cancel() else {
            connect.cancel()
            fail(IOException("Canceled"), null)
        }
    }
}
