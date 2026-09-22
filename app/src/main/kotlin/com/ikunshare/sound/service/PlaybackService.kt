@file:Suppress("DEPRECATION")

package com.ikunshare.sound.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import com.ikunshare.sound.MainActivity
import com.ikunshare.sound.R
import androidx.media.app.NotificationCompat as MediaNotificationCompat

class PlaybackService : Service() {

    companion object {
        const val CHANNEL_ID = "kunyin_playback"
        const val NOTIFICATION_ID = 1
        private const val METADATA_KEY_LYRIC = "android.media.metadata.LYRIC"

        const val ACTION_PLAY_PAUSE = "com.ikunshare.sound.PLAY_PAUSE"
        const val ACTION_NEXT = "com.ikunshare.sound.NEXT"
        const val ACTION_PREV = "com.ikunshare.sound.PREV"
        const val ACTION_SEEK_TO = "com.ikunshare.sound.SEEK_TO"
        const val ACTION_TOGGLE_FAVORITE = "com.ikunshare.sound.TOGGLE_FAVORITE"
        const val ACTION_TOGGLE_FLOATING_LYRICS = "com.ikunshare.sound.TOGGLE_FLOATING_LYRICS"
        const val ACTION_STOP_AND_EXIT = "com.ikunshare.sound.STOP_AND_EXIT"
        const val EXTRA_SEEK_POSITION = "seek_position"

        private var instance: PlaybackService? = null
        fun getInstance(): PlaybackService? = instance
    }

    lateinit var mediaSession: MediaSessionCompat
        private set

    private var currentTitle: String = ""
    private var currentArtist: String = ""
    private var currentAlbum: String = ""
    private var currentCoverBitmap: Bitmap? = null
    private var currentDuration: Long = 0L
    private var isPlaying: Boolean = false
    private var currentLyricText: String = ""
    private var pushLyricToMediaSession: Boolean = false
    private var pushMeizuStatusBarLyrics: Boolean = false
    private var currentIsFavorite: Boolean = false
    private var floatingLyricsEnabled: Boolean = false

    private val meizuFlagAlwaysShowTicker: Int by lazy {
        resolveNotificationFlag("FLAG_ALWAYS_SHOW_TICKER")
    }
    private val meizuFlagOnlyUpdateTicker: Int by lazy {
        resolveNotificationFlag("FLAG_ONLY_UPDATE_TICKER")
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()

        mediaSession = MediaSessionCompat(this, "KunyinPlayer").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    sendBroadcast(Intent(ACTION_PLAY_PAUSE).setPackage(packageName))
                }

                override fun onPause() {
                    sendBroadcast(Intent(ACTION_PLAY_PAUSE).setPackage(packageName))
                }

                override fun onSkipToNext() {
                    sendBroadcast(Intent(ACTION_NEXT).setPackage(packageName))
                }

                override fun onSkipToPrevious() {
                    sendBroadcast(Intent(ACTION_PREV).setPackage(packageName))
                }

                override fun onSeekTo(pos: Long) {
                    sendBroadcast(
                        Intent(ACTION_SEEK_TO)
                            .setPackage(packageName)
                            .putExtra(EXTRA_SEEK_POSITION, pos)
                    )
                }

                override fun onCustomAction(action: String?, extras: Bundle?) {
                    when (action) {
                        ACTION_TOGGLE_FAVORITE ->
                            sendBroadcast(Intent(ACTION_TOGGLE_FAVORITE).setPackage(packageName))

                        ACTION_TOGGLE_FLOATING_LYRICS ->
                            sendBroadcast(
                                Intent(ACTION_TOGGLE_FLOATING_LYRICS).setPackage(
                                    packageName
                                )
                            )
                    }
                }
            })
            isActive = true
        }

        val fgResult = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        }
        if (fgResult.isFailure) {
            instance = null
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 每次 startForegroundService() 都会启动系统计时器，必须响应 startForeground()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        }.onFailure {
            instance = null
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        if (::mediaSession.isInitialized) {
            mediaSession.isActive = false
            mediaSession.release()
        }
        super.onDestroy()
    }

    /**
     * 更新媒体元数据和通知
     */
    fun updateMetadata(
        title: String,
        artist: String,
        album: String,
        duration: Long,
        coverBitmap: Bitmap? = null
    ) {
        currentTitle = title
        currentArtist = artist
        currentAlbum = album
        currentDuration = duration
        currentCoverBitmap = coverBitmap

        publishMetadata()
        updateNotification()
    }

    /**
     * 更新歌词推送状态
     */
    fun updateLyricPush(
        lyricText: String,
        pushToMediaSession: Boolean,
        pushToMeizuStatusBar: Boolean
    ) {
        val normalized = lyricText.trim()
        val lyricChanged = currentLyricText != normalized
        val mediaSessionChanged = pushLyricToMediaSession != pushToMediaSession
        val meizuChanged = pushMeizuStatusBarLyrics != pushToMeizuStatusBar

        currentLyricText = normalized
        pushLyricToMediaSession = pushToMediaSession
        pushMeizuStatusBarLyrics = pushToMeizuStatusBar

        if (lyricChanged || mediaSessionChanged) {
            publishMetadata()
        }
        if (lyricChanged || meizuChanged) {
            updateNotification()
        }
    }

    fun updateFavoriteState(isFavorite: Boolean) {
        if (currentIsFavorite == isFavorite) return
        currentIsFavorite = isFavorite
        applyPlaybackState()
        updateNotification()
    }

    fun updateFloatingLyricsState(enabled: Boolean) {
        if (floatingLyricsEnabled == enabled) return
        floatingLyricsEnabled = enabled
        applyPlaybackState()
        updateNotification()
    }

    private fun publishMetadata() {
        val lyricForMetadata = currentLyricText.takeIf {
            pushLyricToMediaSession && it.isNotBlank()
        }
        val metadata = MediaMetadataCompat.Builder()
            .putString(
                MediaMetadataCompat.METADATA_KEY_TITLE,
                lyricForMetadata ?: currentTitle
            )
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentAlbum)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, currentDuration)
            .putString(
                MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE,
                lyricForMetadata ?: currentTitle
            )
            .putString(
                MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE,
                if (lyricForMetadata != null) "$currentTitle - $currentArtist" else currentArtist
            )
            .putString(
                MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION,
                lyricForMetadata ?: currentAlbum
            )
            .apply {
                currentCoverBitmap?.let {
                    putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
                }
                if (lyricForMetadata != null) {
                    putString(METADATA_KEY_LYRIC, lyricForMetadata)
                }
            }
            .build()

        mediaSession.setMetadata(metadata)
        if (lyricForMetadata != null) {
            mediaSession.setExtras(
                Bundle().apply {
                    putString("lyric", lyricForMetadata)
                    putString(METADATA_KEY_LYRIC, lyricForMetadata)
                }
            )
        } else {
            mediaSession.setExtras(null)
        }
    }

    /**
     * 更新播放状态
     */
    private var currentSpeed: Float = 1.0f

    /** 最近一次已知播放进度，供状态键（收藏/歌词）变化时无损重建 PlaybackState */
    private var lastPlaybackPosition: Long = 0L

    fun updatePlaybackState(playing: Boolean, position: Long, speed: Float = currentSpeed) {
        val stateChanged = isPlaying != playing || currentSpeed != speed
        isPlaying = playing
        currentSpeed = speed
        lastPlaybackPosition = position
        applyPlaybackState()
        // 仅在播放/暂停状态切换时更新通知（避免每200ms重建）
        if (stateChanged) {
            updateNotification()
        }
    }

    /**
     * 用当前播放/进度/收藏/歌词状态重建并下发 PlaybackState。
     * 收藏键、桌面歌词键作为 CustomAction 写入——小米灵动岛/媒体通知的功能键
     * 只从 PlaybackState 的 CustomAction 读取（不读 Notification.addAction）。
     * 状态切换通过更换 iconResId 重建 CustomAction 体现。
     */
    private fun applyPlaybackState() {
        val favoriteAction = PlaybackStateCompat.CustomAction.Builder(
            ACTION_TOGGLE_FAVORITE,
            getString(if (currentIsFavorite) R.string.unfavorite else R.string.favorite),
            if (currentIsFavorite) R.drawable.ic_mi_favorited else R.drawable.ic_mi_favorite
        ).build()
        val lyricsAction = PlaybackStateCompat.CustomAction.Builder(
            ACTION_TOGGLE_FLOATING_LYRICS,
            getString(R.string.floating_lyrics_custom_action),
            if (floatingLyricsEnabled) R.drawable.ic_mi_lyrics_on else R.drawable.ic_mi_lyrics
        ).build()

        val state = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO
            )
            .addCustomAction(favoriteAction)
            .addCustomAction(lyricsAction)
            .setState(
                if (isPlaying) PlaybackStateCompat.STATE_PLAYING
                else PlaybackStateCompat.STATE_PAUSED,
                lastPlaybackPosition,
                if (isPlaying) currentSpeed else 0f
            )
            .build()

        mediaSession.setPlaybackState(state)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "音乐播放",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "音乐播放控制"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIntent = PendingIntent.getBroadcast(
            this, 0,
            Intent(ACTION_PLAY_PAUSE).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nextIntent = PendingIntent.getBroadcast(
            this, 1,
            Intent(ACTION_NEXT).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val prevIntent = PendingIntent.getBroadcast(
            this, 2,
            Intent(ACTION_PREV).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopAndExitIntent = PendingIntent.getBroadcast(
            this, 4,
            Intent(ACTION_STOP_AND_EXIT).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIcon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        val playPauseLabel = if (isPlaying) "暂停" else "播放"
        val stopAndExitLabel = getString(R.string.stop_and_exit_app)

        // 收藏键、桌面歌词键不在此处添加：小米灵动岛/媒体通知只读 PlaybackState 的
        // CustomAction（见 applyPlaybackState），Notification.addAction 在媒体模板下会被忽略。
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(currentTitle.ifEmpty { "坤音" })
            .setContentText(currentArtist.ifEmpty { "未在播放" })
            .setSmallIcon(R.drawable.ic_music_note)
            .setLargeIcon(currentCoverBitmap)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)
            .addAction(R.drawable.ic_skip_prev, "上一首", prevIntent)
            .addAction(playPauseIcon, playPauseLabel, playPauseIntent)
            .addAction(R.drawable.ic_skip_next, "下一首", nextIntent)
            .addAction(R.drawable.ic_close, stopAndExitLabel, stopAndExitIntent)
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        if (pushMeizuStatusBarLyrics && currentLyricText.isNotBlank()) {
            builder.setTicker(currentLyricText)
        }

        val notification = builder.build()
        applyMeizuLyricFlags(notification)
        return notification
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun resolveNotificationFlag(fieldName: String): Int {
        return try {
            val field = Notification::class.java.getDeclaredField(fieldName)
            field.isAccessible = true
            field.getInt(null)
        } catch (_: Exception) {
            0
        }
    }

    private fun applyMeizuLyricFlags(notification: Notification) {
        val showFlag = meizuFlagAlwaysShowTicker
        val updateFlag = meizuFlagOnlyUpdateTicker
        if (showFlag <= 0 || updateFlag <= 0) return

        if (pushMeizuStatusBarLyrics && currentLyricText.isNotBlank()) {
            notification.flags = notification.flags or Notification.FLAG_NO_CLEAR
            notification.flags = notification.flags or showFlag or updateFlag
            notification.extras?.putBoolean("ticker_icon_switch", false)
            notification.extras?.putInt("ticker_icon", R.drawable.ic_music_note)
        } else {
            notification.flags = notification.flags and showFlag.inv() and updateFlag.inv()
        }
    }
}
