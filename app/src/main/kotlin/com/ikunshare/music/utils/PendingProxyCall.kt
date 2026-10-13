package com.ikunshare.music.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.*
import okio.Timeout
import java.io.IOException
import java.io.InterruptedIOException
import kotlin.reflect.KClass
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Defers route creation until the proxy decision, while keeping newCall nonblocking. */
internal class PendingProxyCall(private val base: OkHttpClient, private val original: Request,
    private val routing: ProxyRouting,
    private val create: (OkHttpClient, Request) -> Call) : Call {
    private val stateLock = Any()
    private val listeners = mutableListOf<EventListener>()
    private val tags = ConcurrentHashMap<KClass<*>, Any>()
    private val executed = AtomicBoolean()
    private val cancelled = AtomicBoolean()
    private val pendingTimeout = Timeout().timeout(base.callTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
    @Volatile private var delegate: Call? = null
    override fun addEventListener(eventListener: EventListener) = synchronized(stateLock) {
        val call = delegate
        if (call != null) call.addEventListener(eventListener) else { listeners.add(eventListener); Unit }
    }
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> tag(type: KClass<T>): T? = delegate?.tag(type) ?: (tags[type] as T?) ?: original.tag(type)
    override fun <T> tag(type: Class<out T>): T? = tag(type.kotlin)
    override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T {
        tag(type)?.let { return it }
        val computed = computeIfAbsent()
        return synchronized(stateLock) {
            delegate?.tag(type) { computed } ?: run {
                @Suppress("UNCHECKED_CAST")
                (tags.putIfAbsent(type, computed) ?: computed) as T
            }
        }
    }
    override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = tag(type.kotlin, computeIfAbsent)
    override fun request(): Request = original
    override fun isExecuted(): Boolean = executed.get()
    override fun isCanceled(): Boolean = cancelled.get() || delegate?.isCanceled() == true
    override fun timeout(): Timeout = delegate?.timeout() ?: pendingTimeout
    override fun clone(): Call = PendingProxyCall(base, original, routing, create)
    override fun cancel() { cancelled.set(true); delegate?.cancel() }

    private fun prepare(): Call {
        val start = System.nanoTime()
        fun checkWaiting() {
            if (cancelled.get()) throw IOException("Canceled")
            try { pendingTimeout.throwIfReached() }
            catch (failure: InterruptedIOException) { cancelled.set(true); throw failure }
            val budget = pendingTimeout.timeoutNanos()
            if (budget > 0 && System.nanoTime() - start >= budget) { cancelled.set(true); throw InterruptedIOException("timeout") }
        }
        while (routing.status.value.phase == ProxyPhase.CHECKING && !routing.bypasses(original.url)) {
            checkWaiting()
            try { Thread.sleep(10) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw InterruptedIOException("interrupted") }
        }
        checkWaiting()
        val call = create(base, original)
        val budget = pendingTimeout.timeoutNanos()
        if (budget > 0) call.timeout().timeout((budget - (System.nanoTime() - start)).coerceAtLeast(1), TimeUnit.NANOSECONDS)
        else call.timeout().clearTimeout()
        if (pendingTimeout.hasDeadline()) call.timeout().deadlineNanoTime(pendingTimeout.deadlineNanoTime())
        synchronized(stateLock) {
            tags.forEach { (type, value) ->
                @Suppress("UNCHECKED_CAST")
                call.tag(type as KClass<Any>) { value }
            }
            listeners.forEach(call::addEventListener)
            listeners.clear()
            delegate = call
        }
        if (cancelled.get()) call.cancel()
        return call
    }

    override fun execute(): Response {
        check(executed.compareAndSet(false, true)) { "Already Executed" }
        return prepare().execute()
    }

    override fun enqueue(responseCallback: Callback) {
        check(executed.compareAndSet(false, true)) { "Already Executed" }
        waitScope.launch {
            val call = try { prepare() } catch (failure: IOException) {
                responseCallback.onFailure(this@PendingProxyCall, failure)
                return@launch
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = responseCallback.onFailure(this@PendingProxyCall, e)
                override fun onResponse(call: Call, response: Response) = responseCallback.onResponse(this@PendingProxyCall, response)
            })
        }
    }

    private companion object { val waitScope = CoroutineScope(SupervisorJob() + Dispatchers.IO) }
}
