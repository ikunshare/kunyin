package com.ikunshare.sound.sync

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.ikunshare.sound.database.LocalMusicStore
import com.ikunshare.sound.database.MusicDatabase
import com.ikunshare.sound.database.Playlist
import com.ikunshare.sound.model.MusicItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

/**
 * 自定义歌单是否参与 LX 同步：系统歌单和从 QQ/KG/WY/KW 导入的在线歌单都不参与，
 * 只同步用户自建歌单（`remoteSource` 为空）以及之前已经被 LX 关联过的歌单（`remoteSource == "lx"`）。
 */
private fun Playlist.isLxSyncable(): Boolean =
    !isSystem && (remoteSource == null || remoteSource == "lx")

/**
 * 把 [LocalMusicStore] 中的本地歌单映射为 LX Music 期望的 `ListData`：
 * - 试听列表 (SYSTEM_KIND_TRIAL)  ↔ defaultList
 * - 我的收藏 (SYSTEM_KIND_FAVORITES) ↔ loveList
 * - 其它自定义歌单              ↔ userList[*].list
 *
 * 并注册 RPC 方法响应服务端发起的拉取/写入。
 */
class SyncListBridge(
    private val store: LocalMusicStore,
    /** 同步模式：merge_local_remote / merge_remote_local / overwrite_local_remote / overwrite_remote_local / cancel 等。
     *  与 Go 端 TransMode 的 values 一致。 */
    private val resolveMode: () -> String
) {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()

    /** 当前活跃的 RPC：onOpen 注册时设置，onClosed/onFailure 时清空。 */
    private val activeRpc = AtomicReference<SyncRpc?>(null)

    /** 最近一次与远端对齐的 ListData md5；用来过滤同步回环和无变更推送。 */
    @Volatile
    private var lastSyncedMd5: String? = null

    /** 握手阶段本机交给服务端的 md5。只有服务端 finish 且本地没再变化时，才能把它认作已同步。 */
    @Volatile
    private var pendingHandshakeMd5: String? = null

    @Volatile
    private var handshakeListDataSent: Boolean = false

    @Volatile
    private var initialSyncActive: Boolean = false

    @Volatile
    private var listReady: Boolean = false

    @Volatile
    private var pendingLocalPush: Boolean = false

    @Volatile
    private var pendingReadyForcePush: Boolean = false
    private var watchJob: Job? = null
    private var readyFlushJob: Job? = null

    fun registerHandlers(rpc: SyncRpc) {
        activeRpc.set(rpc)
        pendingHandshakeMd5 = null
        handshakeListDataSent = false
        initialSyncActive = true
        listReady = false
        pendingLocalPush = false
        pendingReadyForcePush = false

        rpc.register("getEnabledFeatures") { _ ->
            // 必须回复一个对象；key → version。Go 端收到的是 SupportedFeatures 映射。
            JsonObject().apply {
                addProperty(SyncProtocol.FEATURE_LIST, SyncProtocol.FEATURE_VERSION_LIST)
                // dislike 目前不启用
            }
        }

        // 服务端按 bare name 调用（path:["list_sync_get_list_data"] 等）
        rpc.register("list_sync_get_md5") { _ ->
            writeLock.withLock {
                md5OfListData(buildListData()).also { pendingHandshakeMd5 = it }
            }
        }
        rpc.register("list_sync_get_list_data") { _ ->
            writeLock.withLock {
                handshakeListDataSent = true
                buildListData().also { pendingHandshakeMd5 = md5OfListData(it) }
            }
        }
        rpc.register("list_sync_set_list_data") { args ->
            val payload = args.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
                ?: throw IllegalArgumentException("list_sync_set_list_data: missing payload")
            writeLock.withLock {
                applyListData(
                    payload,
                    removeMissing = if (initialSyncActive) shouldRemoveMissingOnListData() else true
                )
                lastSyncedMd5 = md5OfListData(buildListData())
            }
            null
        }
        rpc.register("list_sync_get_sync_mode") { _ -> resolveMode() }

        rpc.register("onListSyncAction") { args ->
            val action = args.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
                ?: return@register null
            handleSyncAction(action)
            null
        }

        rpc.register("list_sync_finished") { _ ->
            markHandshakeFinished(delayReadyMs = 500L)
            null
        }
        rpc.register("dislike_sync_finished") { _ -> null }
        rpc.register("finished") { _ ->
            markHandshakeFinished(delayReadyMs = 0L)
            null
        }

        startLocalChangeWatcher()
    }

    /** WebSocket 关闭后调用，停止监听本地变更。 */
    fun unregisterHandlers() {
        activeRpc.set(null)
        watchJob?.cancel()
        watchJob = null
        readyFlushJob?.cancel()
        readyFlushJob = null
        lastSyncedMd5 = null
        pendingHandshakeMd5 = null
        handshakeListDataSent = false
        initialSyncActive = false
        listReady = false
        pendingLocalPush = false
        pendingReadyForcePush = false
    }

    /**
     * 监听本地歌单/歌曲变更，去抖 1.5 秒后若 md5 与上次同步不一致，
     * 则把最新的 ListData 通过 `onListSyncAction(list_data_overwrite)` 推给服务端。
     * 服务端会负责再分发给同账号的其它在线客户端。
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun startLocalChangeWatcher() {
        watchJob?.cancel()
        watchJob = combine(store.playlistsFlow, store.playlistSongsRevision) { _, _ -> }
            .drop(1) // 跳过 StateFlow 的初始值
            .debounce(1500)
            .onEach { tryPushListData() }
            .launchIn(ioScope)
    }

    private suspend fun tryPushListData(force: Boolean = false) {
        val rpc = activeRpc.get() ?: return
        if (!listReady) {
            pendingLocalPush = true
            return
        }
        val payload = writeLock.withLock { buildListData() }
        val md5 = md5OfListData(payload)
        if (!force && md5 == lastSyncedMd5) return
        val result = runCatching {
            rpc.call(
                "onListSyncAction",
                JsonObject().apply {
                    addProperty("action", "list_data_overwrite")
                    add("data", payload)
                }
            )
            lastSyncedMd5 = md5
        }
        result.onFailure {
            android.util.Log.w("SyncListBridge", "push list data failed", it)
            pendingLocalPush = true
        }
    }

    private suspend fun markHandshakeFinished(delayReadyMs: Long) {
        var forcePushAfterFinish = false
        writeLock.withLock {
            if (!initialSyncActive && pendingHandshakeMd5 == null && !handshakeListDataSent) {
                scheduleReadyFlush(delayReadyMs, force = false)
                return
            }
            val current = md5OfListData(buildListData())
            val pending = pendingHandshakeMd5
            val localListWasAccepted =
                handshakeListDataSent && pending != null && current == pending
            if (pendingLocalPush) {
                forcePushAfterFinish = true
            } else if (current == lastSyncedMd5 || localListWasAccepted) {
                lastSyncedMd5 = current
            } else {
                forcePushAfterFinish = activeRpc.get() != null
            }
            pendingHandshakeMd5 = null
            handshakeListDataSent = false
            initialSyncActive = false
        }
        scheduleReadyFlush(delayReadyMs, force = forcePushAfterFinish)
    }

    private fun scheduleReadyFlush(delayReadyMs: Long, force: Boolean) {
        if (force) pendingReadyForcePush = true
        readyFlushJob?.cancel()
        readyFlushJob = ioScope.launch {
            if (delayReadyMs > 0L) delay(delayReadyMs)
            listReady = true
            val shouldPush = pendingLocalPush || pendingReadyForcePush
            pendingLocalPush = false
            pendingReadyForcePush = false
            if (shouldPush) {
                tryPushListData(force = true)
            }
        }
    }

    // ─── 本地 → 远端 ───

    private fun buildListData(): JsonObject {
        val playlists = store.playlistsFlow.value
        val trialPid =
            playlists.firstOrNull { it.systemKind == MusicDatabase.SYSTEM_KIND_TRIAL }?.id ?: 0L
        val favPid =
            playlists.firstOrNull { it.systemKind == MusicDatabase.SYSTEM_KIND_FAVORITES }?.id ?: 0L

        val defaultList =
            if (trialPid > 0L) songsAsLx(store.queryPlaylistSongs(trialPid)) else JsonArray()
        val loveList = if (favPid > 0L) songsAsLx(store.queryPlaylistSongs(favPid)) else JsonArray()

        val userList = JsonArray()
        playlists.forEach { pl ->
            if (!pl.isLxSyncable()) return@forEach
            // 如果还没给这个自定义歌单分配过稳定的远端 id，就生成一个并落地，便于之后 action 精确定位
            val id = pl.remoteId ?: "local_${pl.id}".also {
                store.setRemoteIdDirect(pl.id, "lx", it)
            }
            val songs = store.queryPlaylistSongs(pl.id)
            userList.add(JsonObject().apply {
                addProperty("id", id)
                addProperty("name", pl.name)
                add("list", songsAsLx(songs))
                addProperty("locationUpdateTime", pl.createdAt)
            })
        }

        return JsonObject().apply {
            add("defaultList", defaultList)
            add("loveList", loveList)
            add("userList", userList)
        }
    }

    private fun songsAsLx(items: List<MusicItem>): JsonArray {
        val arr = JsonArray()
        items.forEach {
            val song = LxCodec.songToJson(it)
            if (song.has("id") && song.has("source")) arr.add(song)
        }
        return arr
    }

    // ─── 远端 → 本地 ───

    private fun applyListData(payload: JsonObject, removeMissing: Boolean) {
        val def = payload.getAsJsonArray("defaultList") ?: JsonArray()
        val love = payload.getAsJsonArray("loveList") ?: JsonArray()
        val user = payload.getAsJsonArray("userList") ?: JsonArray()

        // 试听列表
        val trialPid = store.getTrialPlaylistId()
        if (trialPid > 0L) replacePlaylist(trialPid, def, removeMissing = removeMissing)
        // 我的收藏
        val favPid = store.getFavoritesPlaylistId()
        if (favPid > 0L) replacePlaylist(favPid, love, removeMissing = removeMissing)

        // 自定义歌单：按远端 id 优先匹配，否则按名称，都没有就新建
        val now = System.currentTimeMillis()
        val byRemoteId: MutableMap<String, Long> = store.playlistsFlow.value
            .filter { it.remoteSource == "lx" && !it.isSystem && it.remoteId != null }
            .associateByTo(mutableMapOf(), { it.remoteId!! }, { it.id })
        val byName: MutableMap<String, Long> = store.playlistsFlow.value
            .filter { it.isLxSyncable() }
            .associateByTo(mutableMapOf(), { it.name }, { it.id })

        user.forEach { el ->
            val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val remoteId = o.get("id")?.asString
            val name = o.get("name")?.asString ?: return@forEach
            val songsEl = o.getAsJsonArray("list") ?: JsonArray()
            val pid = remoteId?.let { byRemoteId[it] }
                ?: byName[name]
                ?: run {
                    val newId = store.createPlaylistDirect(name, now)
                    if (!remoteId.isNullOrBlank()) store.setRemoteIdDirect(newId, "lx", remoteId)
                    byName[name] = newId
                    if (!remoteId.isNullOrBlank()) byRemoteId[remoteId] = newId
                    newId
                }
            if (pid <= 0L) return@forEach
            // 按 name 匹配上的本地自建歌单，首次同步时也补上 lx 关联，避免之后再被识别为"新歌单"
            if (!remoteId.isNullOrBlank() && byRemoteId[remoteId] != pid) {
                store.setRemoteIdDirect(pid, "lx", remoteId)
                byRemoteId[remoteId] = pid
            }
            replacePlaylist(pid, songsEl, removeMissing = removeMissing)
        }

        store.refreshAfterRestoreSync()
    }

    private fun shouldRemoveMissingOnListData(): Boolean =
        resolveMode() == "overwrite_remote_local"

    private fun replacePlaylist(pid: Long, lxSongs: JsonArray, removeMissing: Boolean) {
        // 读取现有以便比对
        val existing = HashMap<String, ExistingSong>()
        store.queryPlaylistSongsRaw(pid).forEach { (json, _, _) ->
            val item = runCatching { MusicItem.fromJsonString(json) }.getOrNull()
                ?: return@forEach
            existing[songKey(item)] = ExistingSong(item.id, item.getTypeDiscriminator())
        }

        val now = System.currentTimeMillis()
        var pos = 0
        val seen = HashSet<String>()

        lxSongs.forEach { el ->
            val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val item = LxCodec.songFromJson(o) ?: return@forEach
            val key = songKey(item)
            if (key in seen) return@forEach
            seen += key
            if (existing.remove(key) != null) {
                // 已存在 — 这里不调整顺序；若将来需要严格保序可增补 SQL 更新 position
            } else {
                store.addToPlaylistDirect(pid, item.toJson().toString(), now + pos, pos)
            }
            pos++
        }

        // overwrite 才删除远端不存在的本地条目；merge 只追加缺失项。
        if (removeMissing && existing.isNotEmpty()) {
            existing.values.forEach { song ->
                store.removeFromPlaylistDirect(pid, song.id, song.source)
            }
        }
    }

    private data class ExistingSong(val id: Long, val source: String)

    private fun songKey(i: MusicItem) = i.uniqueKey

    // ─── Action 处理 ───

    /** LX 的 list 类动作里目标歌单 id 字段名不稳定，早期叫 listId，现在叫 id。 */
    private fun pickListId(data: JsonObject, vararg keys: String): String? {
        for (k in keys) {
            val v = data.get(k)
            if (v != null && !v.isJsonNull && v.isJsonPrimitive) {
                val s = v.asString
                if (s.isNotBlank()) return s
            }
        }
        return null
    }

    private fun resolveFromData(data: JsonObject): Long {
        val raw = pickListId(data, "id", "listId") ?: return 0L
        return resolvePlaylistId(raw)
    }

    private fun resolvePlaylistId(listId: String): Long = when (listId) {
        "default" -> store.getTrialPlaylistId()
        "love" -> store.getFavoritesPlaylistId()
        else ->
            store.playlistsFlow.value.firstOrNull {
                it.remoteSource == "lx" && it.remoteId == listId && !it.isSystem
            }?.id ?: 0L
    }

    private fun handleSyncAction(action: JsonObject) {
        val kind = action.get("action")?.asString ?: return
        val data = action.get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        ioScope.launch {
            writeLock.withLock {
                runCatching { dispatchAction(kind, data) }
                store.refreshAfterRestoreSync()
                // 把刚刚应用完的状态作为下一次推送的基线，避免立即把对端发来的同一份变更又回推回去
                lastSyncedMd5 = md5OfListData(buildListData())
            }
        }
    }

    private fun dispatchAction(kind: String, data: JsonObject) {
        when (kind) {
            // 兼容旧拼写 list_data_overwire；官方同步服务端使用 list_data_overwrite。
            "list_data_overwire", "list_data_overwrite" -> {
                applyListData(
                    data,
                    removeMissing = if (initialSyncActive) shouldRemoveMissingOnListData() else true
                )
            }

            "list_music_add" -> onMusicAdd(data)
            "list_music_remove" -> onMusicRemove(data)
            "list_music_move" -> onMusicMove(data)
            "list_music_update" -> onMusicUpdate(data)
            "list_music_overwrite" -> onMusicOverwrite(data)
            "list_music_clear" -> onMusicClear(data)
            "list_music_update_position" -> onMusicUpdatePosition(data)
            "list_add" -> onListAdd(data)
            "list_remove" -> onListRemove(data)
            "list_update" -> onListUpdate(data)
            "list_update_position" -> {
                // 本项目内自定义歌单没有保存相对顺序，忽略
            }
            // 以下为只读请求，LX 客户端一般不会推给另一端，忽略
            "list_get", "list_music_get", "list_music_check_exist", "list_music_get_list_ids" -> {}
        }
    }

    // ─── 单条动作实现 ───

    private fun onMusicAdd(data: JsonObject) {
        val pid = resolveFromData(data)
        if (pid <= 0L) return
        val musics = data.getAsJsonArray("musicInfos") ?: return
        val addMusicLocationType = data.get("addMusicLocationType")?.asString ?: "bottom"
        val now = System.currentTimeMillis()
        val items = musics.mapNotNull { (it as? JsonObject)?.let(LxCodec::songFromJson) }
        if (addMusicLocationType == "top") {
            store.shiftPositionsDirect(pid, items.size)
            items.forEachIndexed { idx, item ->
                store.addToPlaylistDirect(pid, item.toJson().toString(), now + idx, idx)
            }
        } else {
            items.forEachIndexed { idx, item ->
                store.appendSongDirect(pid, item.toJson().toString(), now + idx)
            }
        }
    }

    private fun onMusicRemove(data: JsonObject) {
        val pid = resolveFromData(data)
        if (pid <= 0L) return
        val ids = data.getAsJsonArray("ids") ?: data.getAsJsonArray("musicInfos") ?: return

        // 首选：当前歌单内的每一首歌算出 LX id，与待删 id 比对
        val current = store.queryPlaylistSongs(pid)
        val idToLocal: Map<String, MusicItem> = current.associateBy { item ->
            LxCodec.songToJson(item).get("id")?.asString ?: ""
        }
        ids.forEach { el ->
            val lxId = el.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach
            val item = idToLocal[lxId] ?: return@forEach
            store.removeFromPlaylistDirect(pid, item.id, item.getTypeDiscriminator())
        }
    }

    private fun onMusicOverwrite(data: JsonObject) {
        val pid = resolveFromData(data)
        if (pid <= 0L) return
        val musics = data.getAsJsonArray("musicInfos") ?: return
        store.clearPlaylistDirect(pid)
        val now = System.currentTimeMillis()
        musics.forEachIndexed { idx, el ->
            val item = (el as? JsonObject)?.let(LxCodec::songFromJson) ?: return@forEachIndexed
            store.addToPlaylistDirect(pid, item.toJson().toString(), now + idx, idx)
        }
    }

    private fun onMusicClear(data: JsonObject) {
        val pid = resolveFromData(data)
        if (pid <= 0L) return
        store.clearPlaylistDirect(pid)
    }

    private fun onMusicUpdate(data: JsonObject) {
        val pid = resolveFromData(data)
        if (pid <= 0L) return
        val musics = data.getAsJsonArray("musicInfos") ?: return
        val current = store.queryPlaylistSongs(pid)
        val idToLocal: Map<String, MusicItem> = current.associateBy { item ->
            LxCodec.songToJson(item).get("id")?.asString ?: ""
        }
        musics.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val lxId = o.get("id")?.asString ?: return@forEach
            val old = idToLocal[lxId] ?: return@forEach
            val updated = LxCodec.songFromJson(o) ?: return@forEach
            // update = 删除 + 原位置重插
            val pos = currentPosition(pid, old.id, old.getTypeDiscriminator())
            store.removeFromPlaylistDirect(pid, old.id, old.getTypeDiscriminator())
            if (pos >= 0) {
                store.addToPlaylistDirect(
                    pid,
                    updated.toJson().toString(),
                    System.currentTimeMillis(),
                    pos
                )
            } else {
                store.appendSongDirect(pid, updated.toJson().toString(), System.currentTimeMillis())
            }
        }
    }

    private fun currentPosition(pid: Long, songId: Long, source: String): Int {
        val raw = store.queryPlaylistSongsRaw(pid)
        raw.forEach { (json, _, pos) ->
            val item = runCatching { MusicItem.fromJsonString(json) }.getOrNull() ?: return@forEach
            if (item.id == songId && item.getTypeDiscriminator() == source) return pos
        }
        return -1
    }

    private fun onMusicMove(data: JsonObject) {
        val fromRaw = pickListId(data, "fromId", "fromListId") ?: return
        val toRaw = pickListId(data, "toId", "toListId") ?: return
        val fromPid = resolvePlaylistId(fromRaw)
        val toPid = resolvePlaylistId(toRaw)
        if (fromPid <= 0L || toPid <= 0L) return
        val musics = data.getAsJsonArray("musicInfos") ?: return
        val fromSongs = store.queryPlaylistSongs(fromPid)
        val byLxId: Map<String, MusicItem> = fromSongs.associateBy {
            LxCodec.songToJson(it).get("id")?.asString ?: ""
        }
        val now = System.currentTimeMillis()
        musics.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val lxId = o.get("id")?.asString ?: return@forEach
            val item = byLxId[lxId] ?: return@forEach
            store.removeFromPlaylistDirect(fromPid, item.id, item.getTypeDiscriminator())
            store.appendSongDirect(toPid, item.toJson().toString(), now)
        }
    }

    private fun onMusicUpdatePosition(data: JsonObject) {
        val pid = resolveFromData(data)
        if (pid <= 0L) return
        val position = data.get("position")?.asInt ?: return
        val ids = data.getAsJsonArray("ids") ?: return
        val current = store.queryPlaylistSongs(pid)
        val byLxId: Map<String, MusicItem> = current.associateBy {
            LxCodec.songToJson(it).get("id")?.asString ?: ""
        }
        ids.forEachIndexed { offset, el ->
            val lxId = el.asString
            val item = byLxId[lxId] ?: return@forEachIndexed
            store.moveSongInPlaylistDirect(
                pid, item.id, item.getTypeDiscriminator(),
                (position + offset).coerceAtLeast(0)
            )
        }
    }

    private fun onListAdd(data: JsonObject) {
        val lists = data.getAsJsonArray("list") ?: return
        lists.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val name = o.get("name")?.asString ?: return@forEach
            val remoteId = o.get("id")?.asString
            // 按远端 id 查，避免重复创建
            val exists = remoteId?.let {
                store.playlistsFlow.value.firstOrNull { p ->
                    p.remoteSource == "lx" && p.remoteId == it && !p.isSystem
                }
            }
            if (exists != null) return@forEach
            val newPid = store.createPlaylistDirect(name, System.currentTimeMillis())
            if (newPid <= 0L) return@forEach
            if (!remoteId.isNullOrBlank()) {
                store.setRemoteIdDirect(newPid, "lx", remoteId)
            }
            val songsArr = o.getAsJsonArray("list") ?: return@forEach
            songsArr.forEachIndexed { idx, se ->
                val item = (se as? JsonObject)?.let(LxCodec::songFromJson) ?: return@forEachIndexed
                store.addToPlaylistDirect(
                    newPid,
                    item.toJson().toString(),
                    System.currentTimeMillis() + idx,
                    idx
                )
            }
        }
    }

    private fun onListRemove(data: JsonObject) {
        val ids = data.getAsJsonArray("ids") ?: return
        ids.forEach { el ->
            val remoteId = el.asString ?: return@forEach
            val pl = store.playlistsFlow.value.firstOrNull {
                it.remoteSource == "lx" && it.remoteId == remoteId && !it.isSystem
            } ?: return@forEach
            store.deletePlaylistDirect(pl.id)
        }
    }

    private fun onListUpdate(data: JsonObject) {
        val lists = data.getAsJsonArray("list") ?: return
        lists.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val remoteId = o.get("id")?.asString ?: return@forEach
            val name = o.get("name")?.asString ?: return@forEach
            val pl = store.playlistsFlow.value.firstOrNull {
                it.remoteSource == "lx" && it.remoteId == remoteId && !it.isSystem
            } ?: return@forEach
            if (pl.name != name) store.renamePlaylistDirect(pl.id, name)
        }
    }

    private fun md5OfListData(data: JsonElement): String {
        val raw = data.toString().toByteArray(Charsets.UTF_8)
        val md5 = java.security.MessageDigest.getInstance("MD5").digest(raw)
        return md5.joinToString("") { "%02x".format(it) }
    }
}
