package com.ikunshare.sound.sync

import android.content.Context
import com.ikunshare.sound.database.LocalMusicStore
import com.ikunshare.sound.service.SyncService

/** 应用级单例：包装 [SyncClient] 与持久化状态，供 ViewModel/UI 调用。 */
class SyncManager(private val context: Context, store: LocalMusicStore) {
    val state = SyncClientState(context)
    private val bridge = SyncListBridge(store) { state.syncMode }
    val client = SyncClient(state, bridge)

    fun connect() {
        if (state.serverUrl.isBlank() || state.cdk.isBlank()) return
        SyncService.start(context.applicationContext)
        client.connect(state.serverUrl, state.cdk, state.deviceName)
    }

    fun disconnect() {
        client.disconnect()
        SyncService.stop(context.applicationContext)
    }

    fun resetSession() {
        client.disconnect()
        SyncService.stop(context.applicationContext)
        state.clearSession()
    }

    fun startupConnectIfEnabled() {
        if (state.autoConnect) connect()
    }
}
