package com.ikunshare.sound.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.ikunshare.sound.R
import com.ikunshare.sound.model.MusicItem
import kotlinx.coroutines.launch

enum class RedirectPlatform(val source: String, val display: String) {
    QQ("qq", "QQ 音乐"),
    WY("wy", "网易云"),
    KG("kg", "酷狗"),
    KW("kw", "酷我")
}

/** 酷狗歌词候选（UI 层模型，对应 KgProvider.KgLyricCandidate）。 */
data class KgLyricCandidateUi(
    val accessKey: String,
    val downloadId: String,
    val contenttype: Int,
    val song: String,
    val singer: String,
    val language: String,
    val durationMs: Long,
    val score: Int,
    /** 歌词内容类型徽标（如 "逐字"/"翻译"）。空表示尚未探测或探测失败。 */
    val typeBadges: List<String> = emptyList()
)

/**
 * 歌词/封面重定向对话框。
 *
 * QQ / 网易云 / 酷我：输入 id/mid → 查询 → 预览 → 保存（歌词 + 封面一起重定向）。
 * 酷狗：输入关键词 → 搜索歌词候选 → 列表挑选一条 → 保存（仅歌词，accesskey + download_id 直链）。
 *
 * @param lookup        非酷狗平台的查询：key 对 QQ 区分 "id"/"mid"，其它平台用 "id"。
 * @param kgLyricSearch 酷狗歌词候选搜索（关键词 + 源歌曲时长）。
 * @param kgBuildTarget 把选中的酷狗候选构造成可保存的目标 MusicItem。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SongRedirectDialog(
    sourceItem: MusicItem,
    currentRedirect: MusicItem?,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onSave: (MusicItem) -> Unit,
    lookup: suspend (source: String, key: String, value: String) -> MusicItem?,
    kgLyricSearch: (suspend (keyword: String) -> List<KgLyricCandidateUi>)? = null,
    kgBuildTarget: ((KgLyricCandidateUi) -> MusicItem)? = null
) {
    var platform by remember { mutableStateOf(RedirectPlatform.QQ) }
    var qqUseMid by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var kgKeyword by remember {
        mutableStateOf("${sourceItem.artist} - ${sourceItem.title}".trim(' ', '-'))
    }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<MusicItem?>(null) }
    var kgCandidates by remember { mutableStateOf<List<KgLyricCandidateUi>>(emptyList()) }
    var kgSelected by remember { mutableStateOf<KgLyricCandidateUi?>(null) }

    val scope = rememberCoroutineScope()
    val emptyMsg = stringResource(R.string.redirect_input_empty)
    val notFoundMsg = stringResource(R.string.redirect_lookup_failed)
    val isKg = platform == RedirectPlatform.KG

    LaunchedEffect(platform, qqUseMid) {
        preview = null
        error = null
        kgCandidates = emptyList()
        kgSelected = null
    }

    val canSave = if (isKg) kgSelected != null else preview != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.redirect_song_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.redirect_song_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (currentRedirect != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            R.string.redirect_current_target,
                            currentRedirect.title,
                            currentRedirect.artist
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                Spacer(Modifier.height(12.dp))

                Text(
                    stringResource(R.string.redirect_platform),
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RedirectPlatform.entries.forEach { p ->
                        FilterChip(
                            selected = platform == p,
                            onClick = { platform = p },
                            label = { Text(p.display) }
                        )
                    }
                }

                if (platform == RedirectPlatform.QQ) {
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = !qqUseMid,
                            onClick = { qqUseMid = false },
                            label = { Text(stringResource(R.string.redirect_id_or_mid)) }
                        )
                        FilterChip(
                            selected = qqUseMid,
                            onClick = { qqUseMid = true },
                            label = { Text(stringResource(R.string.redirect_use_mid)) }
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                if (isKg) {
                    Text(
                        stringResource(R.string.redirect_kg_lyric_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = kgKeyword,
                        onValueChange = {
                            kgKeyword = it
                            kgCandidates = emptyList()
                            kgSelected = null
                            error = null
                        },
                        singleLine = true,
                        label = { Text(stringResource(R.string.redirect_kg_keyword)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    OutlinedTextField(
                        value = input,
                        onValueChange = {
                            input = it
                            preview = null
                            error = null
                        },
                        singleLine = true,
                        label = {
                            Text(
                                when (platform) {
                                    RedirectPlatform.QQ -> stringResource(
                                        if (qqUseMid) R.string.redirect_mid_qq
                                        else R.string.redirect_id_qq
                                    )

                                    RedirectPlatform.WY -> stringResource(R.string.redirect_id_wy)
                                    RedirectPlatform.KW -> stringResource(R.string.redirect_id_kw)
                                    RedirectPlatform.KG -> stringResource(R.string.redirect_hash_kg)
                                }
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(
                        onClick = {
                            error = null
                            if (isKg) {
                                val kw = kgKeyword.trim()
                                if (kw.isEmpty()) {
                                    error = emptyMsg
                                    return@AssistChip
                                }
                                if (kgLyricSearch == null) return@AssistChip
                                kgCandidates = emptyList()
                                kgSelected = null
                                loading = true
                                scope.launch {
                                    val res = runCatching { kgLyricSearch(kw) }
                                        .getOrDefault(emptyList())
                                    loading = false
                                    if (res.isEmpty()) error = notFoundMsg
                                    else kgCandidates = res
                                }
                            } else {
                                val v = input.trim()
                                if (v.isEmpty()) {
                                    error = emptyMsg
                                    return@AssistChip
                                }
                                preview = null
                                loading = true
                                scope.launch {
                                    val key =
                                        if (platform == RedirectPlatform.QQ && qqUseMid) "mid" else "id"
                                    val result = runCatching {
                                        lookup(platform.source, key, v)
                                    }.getOrNull()
                                    loading = false
                                    if (result == null) error = notFoundMsg
                                    else preview = result
                                }
                            }
                        },
                        enabled = !loading,
                        label = {
                            Text(
                                stringResource(
                                    if (loading) R.string.redirect_looking_up
                                    else R.string.redirect_lookup
                                )
                            )
                        },
                        leadingIcon = {
                            if (loading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        )
                    )
                    if (error != null) {
                        Spacer(Modifier.width(10.dp))
                        Text(
                            error!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                // 酷狗：候选列表挑选
                if (isKg && kgCandidates.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.redirect_kg_pick_candidate),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(Modifier.height(6.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        kgCandidates.forEach { c ->
                            KgCandidateRow(
                                candidate = c,
                                selected = kgSelected === c,
                                onClick = { kgSelected = c }
                            )
                        }
                    }
                }

                // 非酷狗：预览卡片
                preview?.let { item ->
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            if (item.cover.isNotBlank()) {
                                AsyncImage(
                                    model = item.cover,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.title.ifBlank { "(无标题)" },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                item.artist.ifBlank { "(未知)" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (isKg) {
                        val sel = kgSelected ?: return@TextButton
                        kgBuildTarget?.invoke(sel)?.let(onSave)
                    } else {
                        preview?.let(onSave)
                    }
                },
                enabled = canSave
            ) {
                Text(
                    stringResource(R.string.redirect_save),
                    color = if (canSave) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        dismissButton = {
            Row {
                if (currentRedirect != null) {
                    TextButton(onClick = onClear) {
                        Text(
                            stringResource(R.string.redirect_clear),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(
                        stringResource(R.string.cancel),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KgCandidateRow(
    candidate: KgLyricCandidateUi,
    selected: Boolean,
    onClick: () -> Unit
) {
    val sec = candidate.durationMs / 1000
    val durText = "%d:%02d".format(sec / 60, sec % 60)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.tertiaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                candidate.song.ifBlank { "(无标题)" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                buildString {
                    append(candidate.singer.ifBlank { "(未知)" })
                    if (candidate.language.isNotBlank()) append(" · ${candidate.language}")
                    append(" · $durText")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (candidate.typeBadges.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    candidate.typeBadges.forEach { badge ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                badge,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Text(
                "✓",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
    }
}
