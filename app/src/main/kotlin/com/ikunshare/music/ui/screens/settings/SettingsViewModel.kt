package com.ikunshare.music.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikunshare.music.R
import com.ikunshare.music.MusicApplication
import com.ikunshare.music.common.AppSettings
import com.ikunshare.music.common.AppSettingsManager
import com.ikunshare.music.common.CustomThemeEntry
import com.ikunshare.music.database.LocalMusicStore
import com.ikunshare.music.manager.AuthManager
import com.ikunshare.music.manager.AuthState
import com.ikunshare.music.manager.BackupData
import com.ikunshare.music.manager.writeBackupAtomically
import com.ikunshare.music.manager.BackupManager
import com.ikunshare.music.manager.CredentialEntry
import com.ikunshare.music.manager.CredentialManager
import com.ikunshare.music.manager.LxImportData
import com.ikunshare.music.manager.LxImportResult
import com.ikunshare.music.manager.LxMusicImporter
import com.ikunshare.music.manager.MusicRepository
import com.ikunshare.music.manager.RestoreOptions
import com.ikunshare.music.manager.RestoreResult
import com.ikunshare.music.platform.base.ProviderCredentials
import com.ikunshare.music.tool.cache.AudioCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val credentialManager: CredentialManager,
    private val appSettingsManager: AppSettingsManager,
    private val audioCache: AudioCache,
    private val authManager: AuthManager,
    private val musicRepository: MusicRepository,
    val localMusicStore: LocalMusicStore
) : ViewModel() {

    val credentials: StateFlow<Map<String, CredentialEntry>> = credentialManager.credentials
    val appSettings: StateFlow<AppSettings> = appSettingsManager.settings
    val customThemes: StateFlow<List<CustomThemeEntry>> = appSettingsManager.customThemes
    val playlists: StateFlow<List<com.ikunshare.music.database.Playlist>> =
        localMusicStore.playlistsFlow

    /** 激活状态（卡密）。已激活时 isValid=true。 */
    val authState: StateFlow<AuthState> = authManager.state

    private val backupOperations by lazy { BackupOperationRunner(viewModelScope) }
    val isBackupBusy: StateFlow<Boolean> get() = backupOperations.busy

    private val _cacheSizeBytes = MutableStateFlow(audioCache.getCacheSize())
    val cacheSizeBytes: StateFlow<Long> = _cacheSizeBytes.asStateFlow()

    private val _lyricCacheCount = MutableStateFlow(musicRepository.lyricCacheSize())
    val lyricCacheCount: StateFlow<Int> = _lyricCacheCount.asStateFlow()

    private val _mediaInfoCacheCount = MutableStateFlow(musicRepository.mediaInfoCacheSize())
    val mediaInfoCacheCount: StateFlow<Int> = _mediaInfoCacheCount.asStateFlow()

    /** 提交卡密校验，后台执行。 */
    fun submitAuth(authst: String) {
        viewModelScope.launch(Dispatchers.IO) {
            authManager.validateAndSave(authst)
        }
    }

    fun clearAuthError() = authManager.clearError()
    fun logoutAuth() = authManager.logout()

    fun refreshCacheInfo() {
        _cacheSizeBytes.value = audioCache.getCacheSize()
        _lyricCacheCount.value = musicRepository.lyricCacheSize()
        _mediaInfoCacheCount.value = musicRepository.mediaInfoCacheSize()
    }

    fun clearAudioCache() {
        audioCache.clearCache()
        _cacheSizeBytes.value = 0L
    }

    fun clearLyricCache() {
        musicRepository.clearLyricCache()
        _lyricCacheCount.value = 0
    }

    fun clearMediaInfoCache() {
        musicRepository.clearMediaInfoCache()
        _mediaInfoCacheCount.value = 0
    }

    fun clearAllCaches() {
        clearAudioCache()
        clearLyricCache()
        clearMediaInfoCache()
    }

    fun saveCredential(providerKey: String, creds: ProviderCredentials?) {
        credentialManager.saveCredential(providerKey, creds)
    }

    fun clearCredential(providerKey: String) {
        credentialManager.clearCredential(providerKey)
    }

    fun updateSettings(transform: AppSettings.() -> AppSettings) {
        appSettingsManager.update(transform)
    }

    fun updateLyriconEnabled(enabled: Boolean) {
        val app = MusicApplication.instance
        // 先初始化/销毁 adapter，再更新设置——后者会触发 PlayerViewModel 的 collector
        // 立即往 adapter 推送当前歌曲/状态，必须保证那一刻 provider 已经创建。
        if (app != null) {
            if (enabled) app.lyriconAdapter.init(app, appSettingsManager.settings.value.debugLyriconLocalCentral)
            else app.lyriconAdapter.destroy()
        }
        updateSettings { copy(lyriconEnabled = enabled) }
    }

    // ===== 备份与恢复 =====

    private val backupManager: BackupManager by lazy {
        val ctx = MusicApplication.instance!!
        BackupManager(ctx, appSettingsManager, localMusicStore)
    }

    fun exportFullBackup(
        context: android.content.Context,
        dirPath: String,
        fileName: String,
        onResult: (Boolean, String) -> Unit
    ) {
        exportBackup(context, dirPath, fileName, onResult) { backupManager.exportFull(it) }
    }

    fun exportPlaylistsBackup(
        context: android.content.Context,
        dirPath: String,
        fileName: String,
        includeFavorites: Boolean,
        includeTrial: Boolean,
        playlistIds: List<Long>,
        onResult: (Boolean, String) -> Unit
    ) {
        val savedIds = playlistIds.toList()
        exportBackup(context, dirPath, fileName, onResult) {
            backupManager.exportPlaylists(it, includeFavorites, includeTrial, savedIds)
        }
    }

    private fun exportBackup(
        context: android.content.Context,
        dirPath: String,
        fileName: String,
        onResult: (Boolean, String) -> Unit,
        serialize: (java.io.OutputStream) -> Unit
    ) {
        val file = java.io.File(dirPath, fileName)
        backupOperations.submit({ writeBackupAtomically(file, serialize); file.absolutePath }) { result ->
            result.fold(
                onSuccess = { onResult(true, context.getString(R.string.backup_export_success_path, it)) },
                onFailure = { onResult(false, context.getString(R.string.backup_export_failed, it.message ?: "")) }
            )
        }
    }

    fun parseBackupFile(filePath: String, onResult: (BackupData?) -> Unit) {
        backupOperations.submit({
            java.io.File(filePath).inputStream().use { backupManager.parseBackup(it) }
        }) { onResult(it.getOrNull()) }
    }

    fun executeRestore(
        context: android.content.Context,
        data: BackupData,
        options: RestoreOptions,
        onResult: (RestoreResult?) -> Unit
    ) {
        backupOperations.submit({ backupManager.restore(data, options) }) { onResult(it.getOrNull()) }
    }

    // ===== LX Music 导入 =====
    private val lxImporter: LxMusicImporter by lazy { LxMusicImporter(localMusicStore) }

    fun parseLxFile(filePath: String, onResult: (LxImportData?) -> Unit) {
        backupOperations.submit({
            java.io.File(filePath).inputStream().use { lxImporter.parse(it) }
        }) { onResult(it.getOrNull()) }
    }

    fun executeLxImport(data: LxImportData, onResult: (LxImportResult?) -> Unit) {
        backupOperations.submit({ lxImporter.import(data) }) { onResult(it.getOrNull()) }
    }
}
