package com.ikunshare.sound.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikunshare.sound.R
import com.ikunshare.sound.SoundApplication
import com.ikunshare.sound.common.AppSettings
import com.ikunshare.sound.common.AppSettingsManager
import com.ikunshare.sound.common.CustomThemeEntry
import com.ikunshare.sound.database.LocalMusicStore
import com.ikunshare.sound.manager.AuthManager
import com.ikunshare.sound.manager.AuthState
import com.ikunshare.sound.manager.BackupData
import com.ikunshare.sound.manager.BackupManager
import com.ikunshare.sound.manager.CredentialEntry
import com.ikunshare.sound.manager.CredentialManager
import com.ikunshare.sound.manager.LxImportData
import com.ikunshare.sound.manager.LxImportResult
import com.ikunshare.sound.manager.LxMusicImporter
import com.ikunshare.sound.manager.MusicRepository
import com.ikunshare.sound.manager.RestoreOptions
import com.ikunshare.sound.manager.RestoreResult
import com.ikunshare.sound.platform.base.ProviderCredentials
import com.ikunshare.sound.tool.cache.AudioCache
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
    val playlists: StateFlow<List<com.ikunshare.sound.database.Playlist>> =
        localMusicStore.playlistsFlow

    /** 激活状态（卡密）。已激活时 isValid=true。 */
    val authState: StateFlow<AuthState> = authManager.state

    private val _isBackupBusy = MutableStateFlow(false)
    val isBackupBusy: StateFlow<Boolean> = _isBackupBusy.asStateFlow()

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
        val app = SoundApplication.instance
        // 先初始化/销毁 adapter，再更新设置——后者会触发 PlayerViewModel 的 collector
        // 立即往 adapter 推送当前歌曲/状态，必须保证那一刻 provider 已经创建。
        if (app != null) {
            if (enabled) app.lyriconAdapter.init(app)
            else app.lyriconAdapter.destroy()
        }
        updateSettings { copy(lyriconEnabled = enabled) }
    }

    // ===== 备份与恢复 =====

    private val backupManager: BackupManager by lazy {
        val ctx = SoundApplication.instance!!
        BackupManager(ctx, appSettingsManager, localMusicStore)
    }

    fun exportFullBackup(
        context: android.content.Context,
        dirPath: String,
        fileName: String,
        onResult: (Boolean, String) -> Unit
    ) {
        if (_isBackupBusy.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isBackupBusy.value = true
            try {
                val file = java.io.File(dirPath, fileName)
                file.outputStream().use { backupManager.exportFull(it) }
                launch(Dispatchers.Main) {
                    onResult(
                        true,
                        context.getString(R.string.backup_export_success_path, file.absolutePath)
                    )
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    onResult(
                        false,
                        context.getString(R.string.backup_export_failed, e.message ?: "")
                    )
                }
            } finally {
                _isBackupBusy.value = false
            }
        }
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
        if (_isBackupBusy.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isBackupBusy.value = true
            try {
                val file = java.io.File(dirPath, fileName)
                file.outputStream().use {
                    backupManager.exportPlaylists(it, includeFavorites, includeTrial, playlistIds)
                }
                launch(Dispatchers.Main) {
                    onResult(
                        true,
                        context.getString(R.string.backup_export_success_path, file.absolutePath)
                    )
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    onResult(
                        false,
                        context.getString(R.string.backup_export_failed, e.message ?: "")
                    )
                }
            } finally {
                _isBackupBusy.value = false
            }
        }
    }

    fun parseBackupFile(
        filePath: String,
        onResult: (BackupData?) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val data = java.io.File(filePath).inputStream()
                    .use { backupManager.parseBackup(it) }
                launch(Dispatchers.Main) { onResult(data) }
            } catch (_: Exception) {
                launch(Dispatchers.Main) { onResult(null) }
            }
        }
    }

    fun executeRestore(
        context: android.content.Context,
        data: BackupData,
        options: RestoreOptions,
        onResult: (RestoreResult?) -> Unit
    ) {
        if (_isBackupBusy.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isBackupBusy.value = true
            try {
                val result = backupManager.restore(data, options)
                launch(Dispatchers.Main) { onResult(result) }
            } catch (_: Exception) {
                launch(Dispatchers.Main) { onResult(null) }
            } finally {
                _isBackupBusy.value = false
            }
        }
    }

    // ===== LX Music 导入 =====

    private val lxImporter: LxMusicImporter by lazy { LxMusicImporter(localMusicStore) }

    fun parseLxFile(
        filePath: String,
        onResult: (LxImportData?) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val data = runCatching {
                java.io.File(filePath).inputStream().use { lxImporter.parse(it) }
            }.getOrNull()
            launch(Dispatchers.Main) { onResult(data) }
        }
    }

    fun executeLxImport(data: LxImportData, onResult: (LxImportResult?) -> Unit) {
        if (_isBackupBusy.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isBackupBusy.value = true
            val result = runCatching { lxImporter.import(data) }.getOrNull()
            _isBackupBusy.value = false
            launch(Dispatchers.Main) { onResult(result) }
        }
    }
}
