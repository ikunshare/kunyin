package com.ikunshare.music.sync

import android.app.Activity
import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.ikunshare.music.database.LocalMusicStore

/**
 * 应用级单例：包装 [SyncClient] 与持久化状态，供 ViewModel/UI 调用。
 *
 * 保活策略（轻量）：不使用前台服务，也不申请电池优化白名单。
 * - 只在应用位于前台时保持连接；退到后台后保留 [BACKGROUND_GRACE_MS] 让进行中的同步收尾，然后静默断开。
 * - 回到前台时自动重连，服务端会重新做一次完整同步，后台期间的远端变更不会丢。
 * - 断网时不做重试，网络恢复后立即重连。
 */
class SyncManager(private val app: Application, store: LocalMusicStore) {
    val state = SyncClientState(app)
    private val bridge = SyncListBridge(store) { state.syncMode }
    private val main = Handler(Looper.getMainLooper())

    /** 用户希望保持同步（手动连接或勾选了自动连接），disconnect() 清除。 */
    @Volatile
    private var wanted = false

    @Volatile
    private var foreground = false

    @Volatile
    private var networkAvailable = true

    private var startedActivities = 0

    val client = SyncClient(state, bridge, reconnectAllowed = { wanted && foreground && networkAvailable })

    private val pauseForBackground = Runnable {
        if (!foreground) client.disconnect()
    }

    init {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (startedActivities++ == 0) onForeground()
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0) onBackground()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        registerNetworkCallback()
        // 旧版本的同步前台服务留下的通知渠道，已不再使用。
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            runCatching {
                app.getSystemService(android.app.NotificationManager::class.java)
                    ?.deleteNotificationChannel(LEGACY_NOTIFICATION_CHANNEL)
            }
        }
    }

    fun connect() {
        if (state.serverUrl.isBlank() || state.cdk.isBlank()) return
        wanted = true
        main.removeCallbacks(pauseForBackground)
        client.connect(state.serverUrl, state.cdk, state.deviceName)
    }

    fun disconnect() {
        wanted = false
        main.removeCallbacks(pauseForBackground)
        client.disconnect()
    }

    fun resetSession() {
        disconnect()
        state.clearSession()
    }

    /** 进程启动时只记下意图；真正的连接等第一个界面进入前台再建立。 */
    fun startupConnectIfEnabled() {
        if (state.autoConnect && state.serverUrl.isNotBlank() && state.cdk.isNotBlank()) wanted = true
    }

    private fun onForeground() {
        foreground = true
        main.removeCallbacks(pauseForBackground)
        if (wanted && networkAvailable) client.connect(state.serverUrl, state.cdk, state.deviceName)
    }

    private fun onBackground() {
        foreground = false
        if (wanted) {
            main.removeCallbacks(pauseForBackground)
            main.postDelayed(pauseForBackground, BACKGROUND_GRACE_MS)
        }
    }

    private fun registerNetworkCallback() {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        networkAvailable = cm.activeNetwork?.let(cm::getNetworkCapabilities)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val wasOffline = !networkAvailable
                    networkAvailable = true
                    // 网络恢复：跳过退避等待，立即重连。
                    if (wasOffline && wanted && foreground) client.retryNow()
                }

                override fun onLost(network: Network) {
                    networkAvailable = false
                }
            })
        }.onFailure {
            // 拿不到网络回调时退化为普通退避重连。
            networkAvailable = true
        }
    }

    private companion object {
        const val BACKGROUND_GRACE_MS = 3 * 60_000L
        const val LEGACY_NOTIFICATION_CHANNEL = "kunyin_sync"
    }
}
