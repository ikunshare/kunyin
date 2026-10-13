package com.ikunshare.music.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.google.gson.JsonParser
import com.ikunshare.music.model.MusicItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable

class LocalMusicStore(context: Context) {

    private val db = MusicDatabase(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val trialWriteMutex = Mutex()

    private val _playlistsFlow = MutableStateFlow<List<Playlist>>(emptyList())
    val playlistsFlow: StateFlow<List<Playlist>> = _playlistsFlow.asStateFlow()

    /** 某个歌单的歌曲内容被改动时 bump 对应 key 的版本号；0L 表示"任意歌单"广播。 */
    private val _playlistSongsRevision = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val playlistSongsRevision: StateFlow<Map<Long, Long>> = _playlistSongsRevision.asStateFlow()
    private val _removedPlaylistSongs = MutableSharedFlow<Set<String>>(extraBufferCapacity = 64)
    val removedPlaylistSongs = _removedPlaylistSongs.asSharedFlow()

    private val restoreRevisions = ThreadLocal<MutableSet<Long>>()

    fun bumpPlaylistSongsRevision(playlistId: Long) {
        restoreRevisions.get()?.let { it.add(playlistId); return }
        _playlistSongsRevision.update { cur ->
            cur + (playlistId to ((cur[playlistId] ?: 0L) + 1L))
        }
    }

    /**
     * 我的收藏歌单的当前歌曲列表（供需要 favoritesFlow 的调用方使用）。
     */
    private val _favoritesFlow = MutableStateFlow<List<MusicItem>>(emptyList())
    val favoritesFlow: StateFlow<List<MusicItem>> = _favoritesFlow.asStateFlow()

    // 收藏快速判定缓存
    private val favoriteKeys = mutableSetOf<String>()

    // 重定向缓存：key="$id_$source" -> 目标 MusicItem
    private val redirectCache = mutableMapOf<String, MusicItem>()
    private val _redirectKeysFlow = MutableStateFlow<Set<String>>(emptySet())
    val redirectKeysFlow: StateFlow<Set<String>> = _redirectKeysFlow.asStateFlow()

    // 系统歌单 ID（在 init 时填充；MusicDatabase 保证 seed 已发生）
    @Volatile
    private var trialPlaylistIdCache: Long = -1L

    @Volatile
    private var favoritesPlaylistIdCache: Long = -1L

    private val initialization = scope.launch {
        resolveSystemPlaylistIds()
        refreshRedirectCache()
        refreshAll()
    }

    private fun songKey(item: MusicItem): String {
        val source = item.getTypeDiscriminator()
        return "${item.storageId}_$source"
    }

    private fun songSource(item: MusicItem): String = item.getTypeDiscriminator()

    private fun upsertSong(wdb: SQLiteDatabase, item: MusicItem, source: String) {
        val values = ContentValues().apply {
            put(MusicDatabase.COL_SONG_ID, item.storageId)
            put(MusicDatabase.COL_SOURCE, source)
            put(MusicDatabase.COL_SONG_JSON, item.toJson().toString())
        }
        val inserted = wdb.insertWithOnConflict(
            MusicDatabase.TABLE_SONGS, null, values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        check(inserted != -1L) { "Failed to persist song" }
    }

    private fun resolveSystemPlaylistIds() {
        trialPlaylistIdCache = querySystemPlaylistId(MusicDatabase.SYSTEM_KIND_TRIAL)
        favoritesPlaylistIdCache = querySystemPlaylistId(MusicDatabase.SYSTEM_KIND_FAVORITES)
    }

    private fun querySystemPlaylistId(kind: String): Long {
        return db.readableDatabase.rawQuery(
            "SELECT ${MusicDatabase.COL_PLAYLIST_ID} FROM ${MusicDatabase.TABLE_PLAYLISTS} " +
                    "WHERE ${MusicDatabase.COL_SYSTEM_KIND} = ? LIMIT 1",
            arrayOf(kind)
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }
    }

    /** 试听列表的 playlistId。若被异常删除会自动恢复创建。 */
    fun getTrialPlaylistId(): Long {
        val cached = trialPlaylistIdCache
        if (cached > 0) return cached
        val ensured = ensureSystemPlaylist(
            MusicDatabase.SYSTEM_KIND_TRIAL,
            MusicDatabase.SYSTEM_TRIAL_NAME,
            createdAt = 2L
        )
        trialPlaylistIdCache = ensured
        return ensured
    }

    /** 我的收藏的 playlistId。 */
    fun getFavoritesPlaylistId(): Long {
        val cached = favoritesPlaylistIdCache
        if (cached > 0) return cached
        val ensured = ensureSystemPlaylist(
            MusicDatabase.SYSTEM_KIND_FAVORITES,
            MusicDatabase.SYSTEM_FAVORITES_NAME,
            createdAt = 1L
        )
        favoritesPlaylistIdCache = ensured
        return ensured
    }

    private fun ensureSystemPlaylist(kind: String, name: String, createdAt: Long): Long {
        val existing = querySystemPlaylistId(kind)
        if (existing > 0) return existing
        val wdb = db.writableDatabase
        val values = ContentValues().apply {
            put(MusicDatabase.COL_NAME, name)
            put(MusicDatabase.COL_CREATED_AT, createdAt)
            put(MusicDatabase.COL_AUTO_REFRESH, 0)
            put(MusicDatabase.COL_IS_SYSTEM, 1)
            put(MusicDatabase.COL_SYSTEM_KIND, kind)
        }
        val id = wdb.insert(MusicDatabase.TABLE_PLAYLISTS, null, values)
        refreshPlaylists()
        return id
    }

    // ===== 收藏（基于「我的收藏」系统歌单） =====

    fun isFavorite(item: MusicItem): Boolean = synchronized(favoriteKeys) {
        favoriteKeys.contains(songKey(item))
    }

    fun addFavorite(item: MusicItem) {
        val pid = getFavoritesPlaylistId()
        if (pid <= 0) return
        addToPlaylist(pid, item)
    }

    fun removeFavorite(item: MusicItem) {
        val pid = getFavoritesPlaylistId()
        if (pid <= 0) return
        removeFromPlaylist(pid, item, notifyPlayback = false)
    }

    // ===== 试听列表 =====

    /**
     * 将歌曲加入试听列表。
     * @param atHead 为 true 时插入到最前；否则追加到末尾。
     */
    fun addToTrial(item: MusicItem, atHead: Boolean) {
        scope.launch {
            try { addToTrialAndWait(item, atHead) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { android.util.Log.w("LocalMusicStore", "Add trial song failed", failure) }
        }
    }

    suspend fun addToTrialAndWait(item: MusicItem, atHead: Boolean) {
        writeTrial(item, atHead, readSongs = false)
    }

    /** Commit before reading the full queue; serialize requests so rapid clicks retain their order. */
    suspend fun addToTrialAndRead(item: MusicItem, atHead: Boolean): List<MusicItem> =
        writeTrial(item, atHead, readSongs = true)

    private suspend fun writeTrial(item: MusicItem, atHead: Boolean, readSongs: Boolean): List<MusicItem> =
        trialWriteMutex.withLock {
            withContext(Dispatchers.IO) {
                initialization.join()
                coroutineContext.ensureActive()
                val pid = getTrialPlaylistId()
                check(pid > 0) { "Trial playlist is unavailable" }
                val wdb = db.writableDatabase
                var changed = false
                wdb.beginTransaction()
                try {
                    if (atHead) {
                        prependSongInTransaction(wdb, pid, item)
                        changed = true
                    } else {
                        changed = appendSongsInTransaction(wdb, pid, listOf(item)) > 0
                    }
                    wdb.setTransactionSuccessful()
                } finally {
                    wdb.endTransaction()
                }
                if (changed) {
                    bumpPlaylistSongsRevision(pid)
                    refreshPlaylists()
                }
                if (readSongs) queryPlaylistSongs(pid) else emptyList()
            }
        }

    /** 获取试听列表完整歌曲列表（同步，调用方应在 IO 上下文内调用）。 */
    fun queryTrialSongs(): List<MusicItem> {
        val pid = getTrialPlaylistId()
        if (pid <= 0) return emptyList()
        return queryPlaylistSongs(pid)
    }

    // ===== 自定义歌单管理 =====

    fun createPlaylist(
        name: String,
        remoteSource: String? = null,
        remoteId: String? = null,
        autoRefresh: Boolean = false
    ) {
        scope.launch {
            val values = ContentValues().apply {
                put(MusicDatabase.COL_NAME, name)
                put(MusicDatabase.COL_CREATED_AT, System.currentTimeMillis())
                remoteSource?.let { put(MusicDatabase.COL_REMOTE_SOURCE, it) }
                remoteId?.let { put(MusicDatabase.COL_REMOTE_ID, it) }
                put(MusicDatabase.COL_AUTO_REFRESH, if (autoRefresh) 1 else 0)
                put(MusicDatabase.COL_IS_SYSTEM, 0)
            }
            db.writableDatabase.insert(MusicDatabase.TABLE_PLAYLISTS, null, values)
            refreshPlaylists()
        }
    }

    fun deletePlaylist(playlistId: Long) {
        scope.launch {
            val wdb = db.writableDatabase
            // 系统歌单不允许删除
            val isSystem = wdb.rawQuery(
                "SELECT ${MusicDatabase.COL_IS_SYSTEM} FROM ${MusicDatabase.TABLE_PLAYLISTS} " +
                        "WHERE ${MusicDatabase.COL_PLAYLIST_ID} = ?",
                arrayOf(playlistId.toString())
            ).use { c -> if (c.moveToFirst()) c.getInt(0) == 1 else false }
            if (isSystem) return@launch
            wdb.delete(
                MusicDatabase.TABLE_PLAYLIST_SONGS,
                "${MusicDatabase.COL_PLAYLIST_ID} = ?",
                arrayOf(playlistId.toString())
            )
            wdb.delete(
                MusicDatabase.TABLE_PLAYLISTS,
                "${MusicDatabase.COL_PLAYLIST_ID} = ?",
                arrayOf(playlistId.toString())
            )
            bumpPlaylistSongsRevision(0L)
            refreshPlaylists()
        }
    }

    fun addToPlaylist(playlistId: Long, item: MusicItem) {
        scope.launch {
            try {
                addSongsToPlaylist(playlistId, listOf(item))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                android.util.Log.e("LocalMusicStore", "Add song failed", failure)
            }
        }
    }

    /** Wait for one ordered batch to commit before reporting success to the UI. */
    suspend fun addSongsToPlaylist(playlistId: Long, items: List<MusicItem>): Int {
        val snapshot = items.toList()
        if (snapshot.isEmpty()) return 0
        return withContext(Dispatchers.IO) {
            initialization.join()
            val wdb = db.writableDatabase
            var added = 0
            wdb.beginTransaction()
            try {
                val exists = wdb.rawQuery(
                    "SELECT 1 FROM ${MusicDatabase.TABLE_PLAYLISTS} WHERE ${MusicDatabase.COL_PLAYLIST_ID} = ?",
                    arrayOf(playlistId.toString())
                ).use { it.moveToFirst() }
                check(exists) { "Playlist no longer exists" }
                added = appendSongsInTransaction(wdb, playlistId, snapshot)
                wdb.setTransactionSuccessful()
            } finally {
                wdb.endTransaction()
            }
            if (added > 0) bumpPlaylistSongsRevision(playlistId)
            refreshPlaylists()
            added
        }
    }

    /** Sort the complete playlist in one transaction; keep metadata and original added-at timestamps. */
    suspend fun sortPlaylistSongs(playlistId: Long, field: PlaylistSortField, order: PlaylistSortOrder): Int =
        withContext(Dispatchers.IO) {
            initialization.join()
            val wdb = db.writableDatabase
            var count = 0
            wdb.beginTransaction()
            try {
                val exists = wdb.rawQuery(
                    "SELECT 1 FROM ${MusicDatabase.TABLE_PLAYLISTS} WHERE ${MusicDatabase.COL_PLAYLIST_ID} = ?",
                    arrayOf(playlistId.toString())
                ).use { it.moveToFirst() }
                check(exists) { "Playlist no longer exists" }
                // Parse raw rows so corrupt data cannot silently disappear during reordering.
                val songs = queryPlaylistSongsRaw(playlistId).map { MusicItem.fromJsonString(it.first) }
                count = songs.size
                if (count > 1) {
                    val sorted = sortedPlaylistSongs(songs, field, order)
                    wdb.compileStatement(
                        "UPDATE ${MusicDatabase.TABLE_PLAYLIST_SONGS} SET ${MusicDatabase.COL_POSITION} = ? " +
                            "WHERE ${MusicDatabase.COL_PLAYLIST_ID} = ? AND ${MusicDatabase.COL_SONG_ID} = ? AND ${MusicDatabase.COL_SOURCE} = ?"
                    ).use { statement ->
                        sorted.forEachIndexed { position, song ->
                            coroutineContext.ensureActive()
                            statement.bindLong(1, position.toLong())
                            statement.bindLong(2, playlistId)
                            statement.bindLong(3, song.storageId)
                            statement.bindString(4, songSource(song))
                            check(statement.executeUpdateDelete() == 1) { "Playlist changed while sorting" }
                        }
                    }
                }
                wdb.setTransactionSuccessful()
            } finally {
                wdb.endTransaction()
            }
            if (count > 1) {
                bumpPlaylistSongsRevision(playlistId)
                refreshPlaylists()
            }
            count
        }

    /** Replace only after fetching a verified complete remote list; failures leave the old list intact. */
    suspend fun replacePlaylistSongs(playlistId: Long, items: List<MusicItem>): Int {
        val snapshot = items.toList()
        require(snapshot.isNotEmpty()) { "Cannot replace a playlist with an unavailable remote list" }
        return withContext(Dispatchers.IO) {
            initialization.join()
            val wdb = db.writableDatabase
            val count: Int
            wdb.beginTransaction()
            try {
                val exists = wdb.rawQuery(
                    "SELECT 1 FROM ${MusicDatabase.TABLE_PLAYLISTS} WHERE ${MusicDatabase.COL_PLAYLIST_ID} = ?",
                    arrayOf(playlistId.toString())
                ).use { it.moveToFirst() }
                check(exists) { "Playlist no longer exists" }
                wdb.delete(MusicDatabase.TABLE_PLAYLIST_SONGS,
                    "${MusicDatabase.COL_PLAYLIST_ID} = ?", arrayOf(playlistId.toString()))
                count = appendSongsInTransaction(wdb, playlistId, snapshot)
                wdb.setTransactionSuccessful()
            } finally {
                wdb.endTransaction()
            }
            bumpPlaylistSongsRevision(playlistId)
            refreshPlaylists()
            count
        }
    }

    /** Create the playlist and its complete contents atomically, off the main thread. */
    suspend fun createPlaylistWithSongs(
        name: String,
        items: List<MusicItem>,
        remoteSource: String? = null,
        remoteId: String? = null,
        autoRefresh: Boolean = false
    ): Long {
        val snapshot = items.toList()
        require(snapshot.isNotEmpty()) { "Cannot create a playlist without songs" }
        return withContext(Dispatchers.IO) {
            initialization.join()
            val wdb = db.writableDatabase
            val playlistId: Long
            wdb.beginTransaction()
            try {
                val values = ContentValues().apply {
                    put(MusicDatabase.COL_NAME, name)
                    put(MusicDatabase.COL_CREATED_AT, System.currentTimeMillis())
                    remoteSource?.let { put(MusicDatabase.COL_REMOTE_SOURCE, it) }
                    remoteId?.let { put(MusicDatabase.COL_REMOTE_ID, it) }
                    put(MusicDatabase.COL_AUTO_REFRESH, if (autoRefresh) 1 else 0)
                    put(MusicDatabase.COL_IS_SYSTEM, 0)
                }
                playlistId = wdb.insertOrThrow(MusicDatabase.TABLE_PLAYLISTS, null, values)
                appendSongsInTransaction(wdb, playlistId, snapshot)
                wdb.setTransactionSuccessful()
            } finally {
                wdb.endTransaction()
            }
            bumpPlaylistSongsRevision(playlistId)
            refreshPlaylists()
            playlistId
        }
    }

    private suspend fun appendSongsInTransaction(
        wdb: SQLiteDatabase, playlistId: Long, items: List<MusicItem>
    ): Int {
        val existing = mutableSetOf<Pair<Long, String>>()
        var nextPosition = 0
        wdb.rawQuery(
            "SELECT ${MusicDatabase.COL_SONG_ID}, ${MusicDatabase.COL_SOURCE}, ${MusicDatabase.COL_POSITION} " +
                "FROM ${MusicDatabase.TABLE_PLAYLIST_SONGS} WHERE ${MusicDatabase.COL_PLAYLIST_ID} = ?",
            arrayOf(playlistId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                existing.add(cursor.getLong(0) to cursor.getString(1))
                nextPosition = maxOf(nextPosition, cursor.getInt(2) + 1)
            }
        }
        val addedAt = System.currentTimeMillis()
        var added = 0
        for (item in items) {
            // No suspension inside a SQLite transaction: its ownership is thread-bound.
            coroutineContext.ensureActive()
            val source = songSource(item)
            upsertSong(wdb, item, source)
            if (!existing.add(item.storageId to source)) continue
            val values = ContentValues().apply {
                put(MusicDatabase.COL_PLAYLIST_ID, playlistId)
                put(MusicDatabase.COL_SONG_ID, item.storageId)
                put(MusicDatabase.COL_SOURCE, source)
                put(MusicDatabase.COL_ADDED_AT, addedAt)
                put(MusicDatabase.COL_POSITION, nextPosition++)
            }
            wdb.insertOrThrow(MusicDatabase.TABLE_PLAYLIST_SONGS, null, values)
            added++
        }
        return added
    }

    /**
     * 将歌曲插入到指定歌单的开头（position=0），其余歌曲整体后移 1。
     * 如果歌曲已存在于该歌单，会先移除再插入到 head，从而不会产生重复。
     */
    private fun prependSongInTransaction(wdb: SQLiteDatabase, playlistId: Long, item: MusicItem) {
        val source = songSource(item)
        upsertSong(wdb, item, source)
        wdb.delete(
            MusicDatabase.TABLE_PLAYLIST_SONGS,
            "${MusicDatabase.COL_PLAYLIST_ID} = ? AND ${MusicDatabase.COL_SONG_ID} = ? AND ${MusicDatabase.COL_SOURCE} = ?",
            arrayOf(playlistId.toString(), item.storageId.toString(), source)
        )
        wdb.execSQL(
            "UPDATE ${MusicDatabase.TABLE_PLAYLIST_SONGS} SET ${MusicDatabase.COL_POSITION} = ${MusicDatabase.COL_POSITION} + 1 WHERE ${MusicDatabase.COL_PLAYLIST_ID} = ?",
            arrayOf(playlistId)
        )
        val values = ContentValues().apply {
            put(MusicDatabase.COL_PLAYLIST_ID, playlistId)
            put(MusicDatabase.COL_SONG_ID, item.storageId)
            put(MusicDatabase.COL_SOURCE, source)
            put(MusicDatabase.COL_ADDED_AT, System.currentTimeMillis())
            put(MusicDatabase.COL_POSITION, 0)
        }
        check(wdb.insertWithOnConflict(MusicDatabase.TABLE_PLAYLIST_SONGS, null, values,
            SQLiteDatabase.CONFLICT_ABORT) != -1L) { "Unable to add trial song" }
    }

    fun removeFromPlaylist(playlistId: Long, item: MusicItem, notifyPlayback: Boolean = true) {
        scope.launch {
            try { removeFromPlaylistAndWait(playlistId, item, notifyPlayback) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { android.util.Log.w("LocalMusicStore", "Remove song failed", failure) }
        }
    }

    /** Await the actual removal so UI feedback reflects the committed database state. */
    suspend fun removeFromPlaylistAndWait(playlistId: Long, item: MusicItem, notifyPlayback: Boolean = true): Boolean =
        removeSongsFromPlaylistAndWait(playlistId, listOf(item), notifyPlayback) > 0

    suspend fun removeSongsFromPlaylistAndWait(
        playlistId: Long, items: List<MusicItem>, notifyPlayback: Boolean = true
    ): Int {
        val snapshot = items.distinctBy { it.uniqueKey }
        if (snapshot.isEmpty()) return 0
        return withContext(Dispatchers.IO) {
            initialization.join()
            val wdb = db.writableDatabase
            val removed = linkedSetOf<String>()
            wdb.beginTransaction()
            try {
                snapshot.forEach { item ->
                    coroutineContext.ensureActive()
                    if (wdb.delete(MusicDatabase.TABLE_PLAYLIST_SONGS,
                        "${MusicDatabase.COL_PLAYLIST_ID} = ? AND ${MusicDatabase.COL_SONG_ID} = ? AND ${MusicDatabase.COL_SOURCE} = ?",
                        arrayOf(playlistId.toString(), item.storageId.toString(), songSource(item))) > 0) {
                        removed.add(item.uniqueKey)
                    }
                }
                wdb.setTransactionSuccessful()
            } finally { wdb.endTransaction() }
            if (removed.isNotEmpty()) {
                withContext(NonCancellable) {
                    bumpPlaylistSongsRevision(playlistId)
                    if (notifyPlayback) _removedPlaylistSongs.emit(removed)
                    refreshPlaylists()
                }
            }
            removed.size
        }
    }

    fun moveSongInPlaylist(playlistId: Long, item: MusicItem, newPosition: Int) {
        scope.launch {
            val source = songSource(item)
            val wdb = db.writableDatabase
            val entries = mutableListOf<Triple<Long, String, Int>>()
            val cursor = wdb.rawQuery(
                "SELECT ${MusicDatabase.COL_SONG_ID}, ${MusicDatabase.COL_SOURCE}, ${MusicDatabase.COL_POSITION} FROM ${MusicDatabase.TABLE_PLAYLIST_SONGS} WHERE ${MusicDatabase.COL_PLAYLIST_ID} = ? ORDER BY ${MusicDatabase.COL_POSITION} ASC",
                arrayOf(playlistId.toString())
            )
            cursor.use {
                while (it.moveToNext()) {
                    entries.add(Triple(it.getLong(0), it.getString(1), it.getInt(2)))
                }
            }
            val removed = entries.indexOfFirst { it.first == item.storageId && it.second == source }
            if (removed < 0) return@launch
            val entry = entries.removeAt(removed)
            val clampedPos = newPosition.coerceIn(0, entries.size)
            entries.add(clampedPos, entry)
            entries.forEachIndexed { idx, (sid, src, _) ->
                wdb.execSQL(
                    "UPDATE ${MusicDatabase.TABLE_PLAYLIST_SONGS} SET ${MusicDatabase.COL_POSITION} = ? WHERE ${MusicDatabase.COL_PLAYLIST_ID} = ? AND ${MusicDatabase.COL_SONG_ID} = ? AND ${MusicDatabase.COL_SOURCE} = ?",
                    arrayOf<Any>(idx, playlistId, sid, src)
                )
            }
            bumpPlaylistSongsRevision(playlistId)
            refreshPlaylists()
        }
    }

    suspend fun createPlaylistAndAdd(
        name: String,
        item: MusicItem,
        remoteSource: String? = null,
        remoteId: String? = null,
        autoRefresh: Boolean = false
    ): Long = createPlaylistWithSongs(name, listOf(item), remoteSource, remoteId, autoRefresh)

    fun queryPlaylistSongs(playlistId: Long): List<MusicItem> {
        val items = mutableListOf<MusicItem>()
        val cursor = db.readableDatabase.rawQuery(
            """
            SELECT s.${MusicDatabase.COL_SONG_JSON}
            FROM ${MusicDatabase.TABLE_PLAYLIST_SONGS} ps
            INNER JOIN ${MusicDatabase.TABLE_SONGS} s
                ON ps.${MusicDatabase.COL_SONG_ID} = s.${MusicDatabase.COL_SONG_ID}
                AND ps.${MusicDatabase.COL_SOURCE} = s.${MusicDatabase.COL_SOURCE}
            WHERE ps.${MusicDatabase.COL_PLAYLIST_ID} = ?
            ORDER BY ps.${MusicDatabase.COL_POSITION} ASC
        """, arrayOf(playlistId.toString())
        )
        cursor.use {
            val jsonIdx = it.getColumnIndexOrThrow(MusicDatabase.COL_SONG_JSON)
            while (it.moveToNext()) {
                try {
                    items.add(parseSongWithRedirect(it.getString(jsonIdx)))
                } catch (_: Exception) { /* skip corrupt rows */
                }
            }
        }
        return items
    }

    fun queryPlaylistSongsPaged(playlistId: Long, limit: Int, offset: Int): List<MusicItem> {
        val items = mutableListOf<MusicItem>()
        val cursor = db.readableDatabase.rawQuery(
            """
            SELECT s.${MusicDatabase.COL_SONG_JSON}
            FROM ${MusicDatabase.TABLE_PLAYLIST_SONGS} ps
            INNER JOIN ${MusicDatabase.TABLE_SONGS} s
                ON ps.${MusicDatabase.COL_SONG_ID} = s.${MusicDatabase.COL_SONG_ID}
                AND ps.${MusicDatabase.COL_SOURCE} = s.${MusicDatabase.COL_SOURCE}
            WHERE ps.${MusicDatabase.COL_PLAYLIST_ID} = ?
            ORDER BY ps.${MusicDatabase.COL_POSITION} ASC
            LIMIT ? OFFSET ?
        """, arrayOf(playlistId.toString(), limit.toString(), offset.toString())
        )
        cursor.use {
            val jsonIdx = it.getColumnIndexOrThrow(MusicDatabase.COL_SONG_JSON)
            while (it.moveToNext()) {
                try {
                    val raw = it.getString(jsonIdx)
                    items.add(parseSongWithRedirect(raw))
                } catch (_: Exception) { /* skip corrupt rows */
                }
            }
        }
        return items
    }

    /** 解析歌曲 JSON 时，若存在重定向就把封面替换为目标歌曲的封面。 */
    private fun parseSongWithRedirect(songJson: String): MusicItem {
        if (redirectCache.isEmpty()) return MusicItem.fromJsonString(songJson)
        val obj = JsonParser.parseString(songJson).asJsonObject
        val type = obj.get("type")?.asString ?: return MusicItem.fromJsonString(songJson)
        val item = MusicItem.fromJson(obj)
        val key = "${item.storageId}_${type}"
        val target = redirectCache[key]
        if (target == null || target.cover.isBlank()) return item
        obj.addProperty("cover", target.cover)
        return MusicItem.fromJson(obj)
    }

    /** 通过远程 source 和 ID 查找已收藏的歌单 */
    fun getPlaylistByRemoteId(remoteSource: String, remoteId: String): Playlist? {
        return _playlistsFlow.value.find { it.remoteSource == remoteSource && it.remoteId == remoteId }
    }

    fun setAutoRefresh(playlistId: Long, enabled: Boolean) {
        scope.launch {
            db.writableDatabase.execSQL(
                "UPDATE ${MusicDatabase.TABLE_PLAYLISTS} SET ${MusicDatabase.COL_AUTO_REFRESH} = ? WHERE ${MusicDatabase.COL_PLAYLIST_ID} = ?",
                arrayOf(if (enabled) 1 else 0, playlistId)
            )
            refreshPlaylists()
        }
    }

    fun getPlaylist(playlistId: Long): Playlist? {
        return _playlistsFlow.value.find { it.id == playlistId }
    }

    fun getPlaylistName(playlistId: Long): String? {
        val cursor = db.readableDatabase.query(
            MusicDatabase.TABLE_PLAYLISTS,
            arrayOf(MusicDatabase.COL_NAME),
            "${MusicDatabase.COL_PLAYLIST_ID} = ?",
            arrayOf(playlistId.toString()),
            null, null, null
        )
        return cursor.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }

    private fun refreshAll() {
        refreshPlaylists()
    }

    private fun refreshPlaylists() {
        val playlists = mutableListOf<Playlist>()
        val cursor = db.readableDatabase.rawQuery(
            """
            SELECT p.${MusicDatabase.COL_PLAYLIST_ID},
                   p.${MusicDatabase.COL_NAME},
                   p.${MusicDatabase.COL_CREATED_AT},
                   p.${MusicDatabase.COL_REMOTE_SOURCE},
                   p.${MusicDatabase.COL_REMOTE_ID},
                   p.${MusicDatabase.COL_AUTO_REFRESH},
                   p.${MusicDatabase.COL_IS_SYSTEM},
                   p.${MusicDatabase.COL_SYSTEM_KIND},
                   COUNT(ps.${MusicDatabase.COL_SONG_ID}) as song_count,
                   (SELECT s2.${MusicDatabase.COL_SONG_JSON}
                    FROM ${MusicDatabase.TABLE_PLAYLIST_SONGS} ps2
                    INNER JOIN ${MusicDatabase.TABLE_SONGS} s2
                        ON ps2.${MusicDatabase.COL_SONG_ID} = s2.${MusicDatabase.COL_SONG_ID}
                        AND ps2.${MusicDatabase.COL_SOURCE} = s2.${MusicDatabase.COL_SOURCE}
                    WHERE ps2.${MusicDatabase.COL_PLAYLIST_ID} = p.${MusicDatabase.COL_PLAYLIST_ID}
                    ORDER BY ps2.${MusicDatabase.COL_POSITION} ASC LIMIT 1) as first_song_json
            FROM ${MusicDatabase.TABLE_PLAYLISTS} p
            LEFT JOIN ${MusicDatabase.TABLE_PLAYLIST_SONGS} ps
                ON p.${MusicDatabase.COL_PLAYLIST_ID} = ps.${MusicDatabase.COL_PLAYLIST_ID}
            GROUP BY p.${MusicDatabase.COL_PLAYLIST_ID}
            ORDER BY p.${MusicDatabase.COL_IS_SYSTEM} DESC, p.${MusicDatabase.COL_CREATED_AT} DESC
        """, null
        )
        cursor.use {
            val idIdx = it.getColumnIndexOrThrow(MusicDatabase.COL_PLAYLIST_ID)
            val nameIdx = it.getColumnIndexOrThrow(MusicDatabase.COL_NAME)
            val createdIdx = it.getColumnIndexOrThrow(MusicDatabase.COL_CREATED_AT)
            val remoteSourceIdx = it.getColumnIndexOrThrow(MusicDatabase.COL_REMOTE_SOURCE)
            val remoteIdIdx = it.getColumnIndexOrThrow(MusicDatabase.COL_REMOTE_ID)
            val autoRefreshIdx = it.getColumnIndexOrThrow(MusicDatabase.COL_AUTO_REFRESH)
            val isSystemIdx = it.getColumnIndexOrThrow(MusicDatabase.COL_IS_SYSTEM)
            val systemKindIdx = it.getColumnIndexOrThrow(MusicDatabase.COL_SYSTEM_KIND)
            val countIdx = it.getColumnIndexOrThrow("song_count")
            val jsonIdx = it.getColumnIndexOrThrow("first_song_json")
            while (it.moveToNext()) {
                val coverUrl = try {
                    val json = it.getString(jsonIdx)
                    if (json != null) MusicItem.fromJsonString(json).cover else null
                } catch (_: Exception) {
                    null
                }
                playlists.add(
                    Playlist(
                        id = it.getLong(idIdx),
                        name = it.getString(nameIdx),
                        createdAt = it.getLong(createdIdx),
                        songCount = it.getInt(countIdx),
                        coverUrl = coverUrl,
                        remoteSource = if (it.isNull(remoteSourceIdx)) null else it.getString(
                            remoteSourceIdx
                        ),
                        remoteId = if (it.isNull(remoteIdIdx)) null else it.getString(remoteIdIdx),
                        autoRefresh = it.getInt(autoRefreshIdx) != 0,
                        isSystem = it.getInt(isSystemIdx) != 0,
                        systemKind = if (it.isNull(systemKindIdx)) null else it.getString(
                            systemKindIdx
                        )
                    )
                )
            }
        }
        _playlistsFlow.value = playlists
        refreshFavoritesCache()
    }

    private fun refreshFavoritesCache() {
        val pid = favoritesPlaylistIdCache.takeIf { it > 0 }
            ?: querySystemPlaylistId(MusicDatabase.SYSTEM_KIND_FAVORITES).also {
                favoritesPlaylistIdCache = it
            }
        if (pid <= 0) {
            _favoritesFlow.value = emptyList()
            synchronized(favoriteKeys) { favoriteKeys.clear() }
            return
        }
        val songs = queryPlaylistSongs(pid)
        _favoritesFlow.value = songs
        synchronized(favoriteKeys) {
            favoriteKeys.clear()
            songs.forEach { favoriteKeys.add(songKey(it)) }
        }
    }

    /** 更新歌曲信息（刷新后持久化） */
    fun updateSongInfo(item: MusicItem) {
        val source = songSource(item)
        upsertSong(db.writableDatabase, item, source)
    }

    // ===== 备份与恢复 =====

    /** 查询所有收藏歌曲的原始数据（供备份导出用）。 */
    fun queryAllFavoritesRaw(): List<Pair<String, Long>> {
        val pid = getFavoritesPlaylistId()
        if (pid <= 0) return emptyList()
        val result = mutableListOf<Pair<String, Long>>()
        val cursor = db.readableDatabase.rawQuery(
            """
            SELECT s.${MusicDatabase.COL_SONG_JSON}, ps.${MusicDatabase.COL_ADDED_AT}
            FROM ${MusicDatabase.TABLE_PLAYLIST_SONGS} ps
            INNER JOIN ${MusicDatabase.TABLE_SONGS} s
                ON ps.${MusicDatabase.COL_SONG_ID} = s.${MusicDatabase.COL_SONG_ID}
                AND ps.${MusicDatabase.COL_SOURCE} = s.${MusicDatabase.COL_SOURCE}
            WHERE ps.${MusicDatabase.COL_PLAYLIST_ID} = ?
            ORDER BY ps.${MusicDatabase.COL_POSITION} ASC
        """, arrayOf(pid.toString())
        )
        cursor.use {
            val jsonIdx = it.getColumnIndexOrThrow(MusicDatabase.COL_SONG_JSON)
            val atIdx = it.getColumnIndexOrThrow(MusicDatabase.COL_ADDED_AT)
            while (it.moveToNext()) {
                result.add(it.getString(jsonIdx) to it.getLong(atIdx))
            }
        }
        return result
    }

    fun queryPlaylistSongsRaw(playlistId: Long): List<Triple<String, Long, Int>> {
        val result = mutableListOf<Triple<String, Long, Int>>()
        val cursor = db.readableDatabase.rawQuery(
            """
            SELECT s.${MusicDatabase.COL_SONG_JSON}, ps.${MusicDatabase.COL_ADDED_AT}, ps.${MusicDatabase.COL_POSITION}
            FROM ${MusicDatabase.TABLE_PLAYLIST_SONGS} ps
            INNER JOIN ${MusicDatabase.TABLE_SONGS} s
                ON ps.${MusicDatabase.COL_SONG_ID} = s.${MusicDatabase.COL_SONG_ID}
                AND ps.${MusicDatabase.COL_SOURCE} = s.${MusicDatabase.COL_SOURCE}
            WHERE ps.${MusicDatabase.COL_PLAYLIST_ID} = ?
            ORDER BY ps.${MusicDatabase.COL_POSITION} ASC
        """, arrayOf(playlistId.toString())
        )
        cursor.use {
            val jsonIdx = it.getColumnIndexOrThrow(MusicDatabase.COL_SONG_JSON)
            val atIdx = it.getColumnIndexOrThrow(MusicDatabase.COL_ADDED_AT)
            val posIdx = it.getColumnIndexOrThrow(MusicDatabase.COL_POSITION)
            while (it.moveToNext()) {
                result.add(Triple(it.getString(jsonIdx), it.getLong(atIdx), it.getInt(posIdx)))
            }
        }
        return result
    }

    fun createPlaylistDirect(name: String, createdAt: Long): Long {
        val values = ContentValues().apply {
            put(MusicDatabase.COL_NAME, name)
            put(MusicDatabase.COL_CREATED_AT, createdAt)
            put(MusicDatabase.COL_IS_SYSTEM, 0)
        }
        return db.writableDatabase.insert(MusicDatabase.TABLE_PLAYLISTS, null, values)
    }

    /**
     * 备份恢复：将歌曲加入「我的收藏」系统歌单。
     */
    fun addFavoriteDirect(songJson: String, addedAt: Long, strict: Boolean = false): Boolean =
        appendToPlaylistDirect(getFavoritesPlaylistId(), songJson, addedAt, strict)

    /**
     * 追加单曲到指定歌单尾部（系统歌单合并导入用）。
     * 返回 true=新增；false=歌单已包含此 (songId, source) 或写入失败。
     */
    fun appendToPlaylistDirect(playlistId: Long, songJson: String, addedAt: Long, strict: Boolean = false): Boolean {
        return try {
            val item = MusicItem.fromJsonString(songJson)
            val source = songSource(item)
            val wdb = db.writableDatabase
            check(playlistId > 0 && getPlaylistName(playlistId) != null) { "Playlist does not exist" }
            val exists = wdb.rawQuery(
                "SELECT 1 FROM ${MusicDatabase.TABLE_PLAYLIST_SONGS} " +
                        "WHERE ${MusicDatabase.COL_PLAYLIST_ID}=? AND ${MusicDatabase.COL_SONG_ID}=? AND ${MusicDatabase.COL_SOURCE}=? LIMIT 1",
                arrayOf(playlistId.toString(), item.storageId.toString(), source)
            ).use { it.moveToFirst() }
            if (exists) return false
            upsertSong(wdb, item, source)
            val maxPos = wdb.rawQuery(
                "SELECT MAX(${MusicDatabase.COL_POSITION}) FROM ${MusicDatabase.TABLE_PLAYLIST_SONGS} WHERE ${MusicDatabase.COL_PLAYLIST_ID}=?",
                arrayOf(playlistId.toString())
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else -1 }
            val values = ContentValues().apply {
                put(MusicDatabase.COL_PLAYLIST_ID, playlistId)
                put(MusicDatabase.COL_SONG_ID, item.storageId)
                put(MusicDatabase.COL_SOURCE, source)
                put(MusicDatabase.COL_ADDED_AT, addedAt)
                put(MusicDatabase.COL_POSITION, maxPos + 1)
            }
            val inserted = wdb.insertWithOnConflict(
                MusicDatabase.TABLE_PLAYLIST_SONGS, null, values,
                SQLiteDatabase.CONFLICT_IGNORE
            )
            if (strict) check(inserted != -1L) { "Failed to persist playlist song" }
            if (inserted != -1L) bumpPlaylistSongsRevision(playlistId)
            inserted != -1L
        } catch (failure: Exception) {
            if (strict) throw failure
            false
        }
    }

    fun addToPlaylistDirect(playlistId: Long, songJson: String, addedAt: Long, position: Int, strict: Boolean = false): Boolean {
        return try {
            val item = MusicItem.fromJsonString(songJson)
            val source = songSource(item)
            val wdb = db.writableDatabase
            check(playlistId > 0 && getPlaylistName(playlistId) != null) { "Playlist does not exist" }
            val exists = wdb.rawQuery(
                "SELECT 1 FROM ${MusicDatabase.TABLE_PLAYLIST_SONGS} WHERE ${MusicDatabase.COL_PLAYLIST_ID}=? AND ${MusicDatabase.COL_SONG_ID}=? AND ${MusicDatabase.COL_SOURCE}=? LIMIT 1",
                arrayOf(playlistId.toString(), item.storageId.toString(), source)
            ).use { it.moveToFirst() }
            if (exists) return false
            upsertSong(wdb, item, source)
            val values = ContentValues().apply {
                put(MusicDatabase.COL_PLAYLIST_ID, playlistId)
                put(MusicDatabase.COL_SONG_ID, item.storageId)
                put(MusicDatabase.COL_SOURCE, source)
                put(MusicDatabase.COL_ADDED_AT, addedAt)
                put(MusicDatabase.COL_POSITION, position)
            }
            val inserted = wdb.insertWithOnConflict(
                MusicDatabase.TABLE_PLAYLIST_SONGS, null, values,
                SQLiteDatabase.CONFLICT_IGNORE
            )
            if (strict) check(inserted != -1L) { "Failed to persist playlist song" }
            if (inserted != -1L) bumpPlaylistSongsRevision(playlistId)
            inserted != -1L
        } catch (failure: Exception) {
            if (strict) throw failure
            false
        }
    }

    /** Restore publishes revisions only after the entire database batch commits. */
    fun <T> withRestoreTransaction(action: () -> T): T {
        check(restoreRevisions.get() == null) { "Nested restore transaction" }
        val database = db.writableDatabase
        val revisions = mutableSetOf<Long>()
        database.beginTransaction()
        val result = try {
            restoreRevisions.set(revisions)
            action().also { database.setTransactionSuccessful() }
        } finally {
            restoreRevisions.remove()
            database.endTransaction()
        }
        revisions.forEach(::bumpPlaylistSongsRevision)
        return result
    }

    fun refreshAfterRestore() {
        scope.launch { refreshAfterRestoreSync() }
    }

    fun refreshAfterRestoreSync() {
        refreshAll()
    }

    // ─── 同步用直写方法（同步、非 scope.launch） ───

    /** 按 storageId+source 直接从歌单删除（平台 ID 保留在歌曲 JSON 中）。 */
    fun removeFromPlaylistDirect(playlistId: Long, songId: Long, source: String) {
        db.writableDatabase.delete(
            MusicDatabase.TABLE_PLAYLIST_SONGS,
            "${MusicDatabase.COL_PLAYLIST_ID} = ? AND ${MusicDatabase.COL_SONG_ID} = ? AND ${MusicDatabase.COL_SOURCE} = ?",
            arrayOf(playlistId.toString(), songId.toString(), source)
        )
        bumpPlaylistSongsRevision(playlistId)
    }

    /** 清空歌单下全部歌曲。 */
    fun clearPlaylistDirect(playlistId: Long) {
        db.writableDatabase.delete(
            MusicDatabase.TABLE_PLAYLIST_SONGS,
            "${MusicDatabase.COL_PLAYLIST_ID} = ?",
            arrayOf(playlistId.toString())
        )
        bumpPlaylistSongsRevision(playlistId)
    }

    /** 重命名歌单。 */
    fun renamePlaylistDirect(playlistId: Long, newName: String) {
        val v = ContentValues().apply { put(MusicDatabase.COL_NAME, newName) }
        db.writableDatabase.update(
            MusicDatabase.TABLE_PLAYLISTS, v,
            "${MusicDatabase.COL_PLAYLIST_ID} = ? AND ${MusicDatabase.COL_IS_SYSTEM} = 0",
            arrayOf(playlistId.toString())
        )
        bumpPlaylistSongsRevision(0L)
    }

    /** 直接删除自定义歌单及其全部歌曲。系统歌单不允许删除。 */
    fun deletePlaylistDirect(playlistId: Long) {
        val wdb = db.writableDatabase
        val isSystem = wdb.rawQuery(
            "SELECT ${MusicDatabase.COL_IS_SYSTEM} FROM ${MusicDatabase.TABLE_PLAYLISTS} " +
                    "WHERE ${MusicDatabase.COL_PLAYLIST_ID} = ?",
            arrayOf(playlistId.toString())
        ).use { c -> if (c.moveToFirst()) c.getInt(0) == 1 else false }
        if (isSystem) return
        wdb.delete(
            MusicDatabase.TABLE_PLAYLIST_SONGS,
            "${MusicDatabase.COL_PLAYLIST_ID} = ?", arrayOf(playlistId.toString())
        )
        wdb.delete(
            MusicDatabase.TABLE_PLAYLISTS,
            "${MusicDatabase.COL_PLAYLIST_ID} = ?", arrayOf(playlistId.toString())
        )
        bumpPlaylistSongsRevision(0L)
    }

    /** 把 (songId, source) 在歌单内移到 targetPos，其他条目顺序重排。 */
    fun moveSongInPlaylistDirect(playlistId: Long, songId: Long, source: String, targetPos: Int) {
        val wdb = db.writableDatabase
        val entries = mutableListOf<Pair<Long, String>>()
        wdb.rawQuery(
            "SELECT ${MusicDatabase.COL_SONG_ID}, ${MusicDatabase.COL_SOURCE} FROM ${MusicDatabase.TABLE_PLAYLIST_SONGS} WHERE ${MusicDatabase.COL_PLAYLIST_ID} = ? ORDER BY ${MusicDatabase.COL_POSITION} ASC",
            arrayOf(playlistId.toString())
        ).use { c ->
            while (c.moveToNext()) entries += c.getLong(0) to c.getString(1)
        }
        val idx = entries.indexOfFirst { it.first == songId && it.second == source }
        if (idx < 0) return
        val entry = entries.removeAt(idx)
        entries.add(targetPos.coerceIn(0, entries.size), entry)
        entries.forEachIndexed { i, (sid, src) ->
            wdb.execSQL(
                "UPDATE ${MusicDatabase.TABLE_PLAYLIST_SONGS} SET ${MusicDatabase.COL_POSITION} = ? WHERE ${MusicDatabase.COL_PLAYLIST_ID} = ? AND ${MusicDatabase.COL_SONG_ID} = ? AND ${MusicDatabase.COL_SOURCE} = ?",
                arrayOf<Any>(i, playlistId, sid, src)
            )
        }
        bumpPlaylistSongsRevision(playlistId)
    }

    /** 追加到歌单尾部（不去重），返回分配到的 position。 */
    fun appendSongDirect(playlistId: Long, songJson: String, addedAt: Long): Int {
        val wdb = db.writableDatabase
        val nextPos = wdb.rawQuery(
            "SELECT COALESCE(MAX(${MusicDatabase.COL_POSITION}) + 1, 0) FROM ${MusicDatabase.TABLE_PLAYLIST_SONGS} WHERE ${MusicDatabase.COL_PLAYLIST_ID} = ?",
            arrayOf(playlistId.toString())
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        addToPlaylistDirect(playlistId, songJson, addedAt, nextPos)
        return nextPos
    }

    /** 按名称查找自定义歌单（不含系统歌单）。 */
    fun findCustomPlaylistByName(name: String): Playlist? =
        playlistsFlow.value.firstOrNull { !it.isSystem && it.name == name }

    /** 写入歌单的远端来源/id（用于跟 LX 服务端的歌单 id 关联）。 */
    fun setRemoteIdDirect(playlistId: Long, remoteSource: String, remoteId: String) {
        val v = ContentValues().apply {
            put(MusicDatabase.COL_REMOTE_SOURCE, remoteSource)
            put(MusicDatabase.COL_REMOTE_ID, remoteId)
        }
        db.writableDatabase.update(
            MusicDatabase.TABLE_PLAYLISTS, v,
            "${MusicDatabase.COL_PLAYLIST_ID} = ?",
            arrayOf(playlistId.toString())
        )
        // 同步刷新内存缓存，避免调用方下次读 playlistsFlow 时仍是旧值
        val cur = _playlistsFlow.value
        val idx = cur.indexOfFirst { it.id == playlistId }
        if (idx >= 0) {
            val updated = cur[idx].copy(remoteSource = remoteSource, remoteId = remoteId)
            _playlistsFlow.value = cur.toMutableList().also { it[idx] = updated }
        }
    }

    /** 把歌单内全部歌曲的 position += delta，便于在头部插入新条目。 */
    fun shiftPositionsDirect(playlistId: Long, delta: Int) {
        if (delta == 0) return
        db.writableDatabase.execSQL(
            "UPDATE ${MusicDatabase.TABLE_PLAYLIST_SONGS} SET ${MusicDatabase.COL_POSITION} = ${MusicDatabase.COL_POSITION} + ? WHERE ${MusicDatabase.COL_PLAYLIST_ID} = ?",
            arrayOf<Any>(delta, playlistId)
        )
        bumpPlaylistSongsRevision(playlistId)
    }

    // ===== 歌词/封面重定向 =====

    private fun redirectKey(item: MusicItem): String =
        "${item.storageId}_${songSource(item)}"

    /** 同步获取某首歌的重定向目标（无则返回 null）。 */
    fun getRedirect(item: MusicItem): MusicItem? = redirectCache[redirectKey(item)]

    /** 设置歌曲的歌词/封面来源为 [target]，立即生效。 */
    fun setRedirect(source: MusicItem, target: MusicItem) {
        val key = redirectKey(source)
        val v = ContentValues().apply {
            put(MusicDatabase.COL_SONG_ID, source.storageId)
            put(MusicDatabase.COL_SOURCE, songSource(source))
            put(MusicDatabase.COL_TARGET_SONG_JSON, target.toJson().toString())
            put(MusicDatabase.COL_CREATED_AT, System.currentTimeMillis())
        }
        val wdb = db.writableDatabase
        wdb.beginTransaction()
        try {
            // Keep source metadata even when the source is not in a playlist.
            upsertSong(wdb, source, songSource(source))
            check(wdb.insertWithOnConflict(MusicDatabase.TABLE_SONG_REDIRECTS, null, v,
                SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "Failed to persist redirect" }
            wdb.setTransactionSuccessful()
        } finally { wdb.endTransaction() }
        redirectCache[key] = target
        _redirectKeysFlow.value = redirectCache.keys.toSet()
        refreshFavoritesCache()
    }

    /** 清除歌曲的重定向。 */
    fun clearRedirect(item: MusicItem) {
        val key = redirectKey(item)
        db.writableDatabase.delete(
            MusicDatabase.TABLE_SONG_REDIRECTS,
            "${MusicDatabase.COL_SONG_ID} = ? AND ${MusicDatabase.COL_SOURCE} = ?",
            arrayOf(item.storageId.toString(), songSource(item))
        )
        if (redirectCache.remove(key) != null) {
            _redirectKeysFlow.value = redirectCache.keys.toSet()
            refreshFavoritesCache()
        }
    }

    /** 是否已为该歌曲设置过重定向。 */
    fun hasRedirect(item: MusicItem): Boolean = redirectCache.containsKey(redirectKey(item))

    private fun refreshRedirectCache() {
        redirectCache.clear()
        val cursor = db.readableDatabase.rawQuery(
            "SELECT ${MusicDatabase.COL_SONG_ID}, ${MusicDatabase.COL_SOURCE}, ${MusicDatabase.COL_TARGET_SONG_JSON} " +
                    "FROM ${MusicDatabase.TABLE_SONG_REDIRECTS}",
            null
        )
        cursor.use {
            val idIdx = it.getColumnIndexOrThrow(MusicDatabase.COL_SONG_ID)
            val srcIdx = it.getColumnIndexOrThrow(MusicDatabase.COL_SOURCE)
            val jsonIdx = it.getColumnIndexOrThrow(MusicDatabase.COL_TARGET_SONG_JSON)
            while (it.moveToNext()) {
                try {
                    val target = MusicItem.fromJsonString(it.getString(jsonIdx))
                    redirectCache["${it.getLong(idIdx)}_${it.getString(srcIdx)}"] = target
                } catch (_: Exception) { /* skip */
                }
            }
        }
        _redirectKeysFlow.value = redirectCache.keys.toSet()
    }
}
