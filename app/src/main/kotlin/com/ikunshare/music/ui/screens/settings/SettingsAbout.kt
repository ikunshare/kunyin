package com.ikunshare.music.ui.screens.settings

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.ikunshare.music.BuildConfig
import com.ikunshare.music.MusicApplication
import com.ikunshare.music.R
import com.ikunshare.music.manager.AuthState
import com.ikunshare.music.manager.NoticeInfo
import com.ikunshare.music.manager.NoticeManager
import com.ikunshare.music.manager.UpdateInfo
import com.ikunshare.music.ui.components.NoticeDialog
import com.ikunshare.music.ui.components.UpdateDialog
import com.ikunshare.music.ui.components.liquidGlass
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val REPO_URL = "https://github.com/ikunshare/kunyin"

@Composable
internal fun AboutSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val packageInfo = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (_: Exception) {
            null
        }
    }
    val versionName = packageInfo?.versionName ?: "unknown"

    @Suppress("DEPRECATION")
    val versionCode = packageInfo?.versionCode ?: 0
    val commitSha = BuildConfig.GIT_COMMIT

    // ── 调试入口彩蛋：点图标 5 次后 1 秒内点版本号 ──
    var iconClickCount by remember { mutableIntStateOf(0) }
    var debugUnlockedAt by remember { mutableLongStateOf(0L) }
    var showDebugScreen by remember { mutableStateOf(false) }

    AboutHeroCard(
        versionName = versionName,
        versionCode = versionCode,
        commitSha = commitSha,
        onIconClick = {
            iconClickCount++
            if (iconClickCount >= 5) {
                debugUnlockedAt = System.currentTimeMillis()
                iconClickCount = 0
            }
        },
        onVersionClick = {
            if (debugUnlockedAt > 0 && System.currentTimeMillis() - debugUnlockedAt <= 1000) {
                showDebugScreen = true
            }
            debugUnlockedAt = 0
        }
    )

    Spacer(Modifier.height(16.dp))

    // ── 激活 ──
    val authManager = remember { MusicApplication.instance?.authManager }
    val authState by (authManager?.state
        ?: MutableStateFlow(AuthState())).collectAsState()
    var showAuthDialog by remember { mutableStateOf(false) }
    var authInput by remember { mutableStateOf("") }

    SettingClickableItem(
        label = if (authState.isValid) "已激活" else "激活",
        value = if (authState.isValid) "卡密校验通过" else "点击输入卡密完成激活",
        valueColor = if (authState.isValid) MaterialTheme.colorScheme.tertiary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        icon = Icons.Filled.VerifiedUser,
        onClick = {
            if (!authState.isValid) {
                authInput = ""
                authManager?.clearError()
                showAuthDialog = true
            }
        },
        trailing = if (authState.isValid) {
            {
                Icon(
                    Icons.Filled.CheckCircle, contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(22.dp)
                )
            }
        } else null
    )

    if (showAuthDialog && authManager != null) {
        AlertDialog(
            onDismissRequest = {
                showAuthDialog = false
                authInput = ""
                authManager.clearError()
            },
            title = { Text("激活") },
            text = {
                Column {
                    OutlinedTextField(
                        value = authInput,
                        onValueChange = {
                            authInput = it
                            authManager.clearError()
                        },
                        label = { Text("请输入卡密") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        isError = authState.errorMessage.isNotEmpty(),
                        supportingText = if (authState.errorMessage.isNotEmpty()) {
                            { Text(authState.errorMessage) }
                        } else null,
                        enabled = !authState.isChecking
                    )
                    if (authState.isChecking) {
                        Spacer(Modifier.height(8.dp))
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(24.dp)
                                .align(Alignment.CenterHorizontally)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (authInput.isNotBlank()) {
                            scope.launch(Dispatchers.IO) {
                                val (valid, _) = authManager.validateAndSave(authInput.trim())
                                if (valid) {
                                    launch(Dispatchers.Main) {
                                        showAuthDialog = false
                                        authInput = ""
                                    }
                                }
                            }
                        }
                    },
                    enabled = authInput.isNotBlank() && !authState.isChecking
                ) { Text("确认") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAuthDialog = false
                    authInput = ""
                    authManager.clearError()
                }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    Spacer(Modifier.height(12.dp))

    // ── 快捷操作：检查更新 / 查看公告 / QQ 群 ──
    var isChecking by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var downloadProgress by remember { mutableStateOf<Float?>(null) }
    var downloadFailed by remember { mutableStateOf(false) }
    var isFetchingNotice by remember { mutableStateOf(false) }
    var noticeContent by remember { mutableStateOf<NoticeInfo?>(null) }

    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AboutActionTile(
            icon = Icons.Filled.SystemUpdate,
            label = stringResource(R.string.check_update),
            loading = isChecking,
            modifier = Modifier.weight(1f)
        ) {
            isChecking = true
            scope.launch {
                val app = MusicApplication.instance ?: run { isChecking = false; return@launch }
                val info = app.updateChecker.check()
                isChecking = false
                if (info != null && info.hasUpdate) {
                    downloadProgress = null
                    downloadFailed = false
                    updateInfo = info
                } else {
                    Toast.makeText(
                        context,
                        context.getString(R.string.already_latest),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        AboutActionTile(
            icon = Icons.Filled.Notifications,
            label = stringResource(R.string.view_notice),
            loading = isFetchingNotice,
            modifier = Modifier.weight(1f)
        ) {
            isFetchingNotice = true
            scope.launch {
                val app = MusicApplication.instance ?: run { isFetchingNotice = false; return@launch }
                val notice = withContext(Dispatchers.IO) {
                    app.noticeManager.fetch(NoticeManager.NOTICE_API_URL, versionName, versionCode)
                }
                isFetchingNotice = false
                if (notice != null) {
                    noticeContent = notice
                } else {
                    Toast.makeText(context, context.getString(R.string.no_notice), Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }
        AboutActionTile(
            icon = Icons.Filled.Groups,
            label = stringResource(R.string.join_qq_group),
            modifier = Modifier.weight(1f)
        ) {
            try {
                val key = "A2alOAv2htz2-egCuBfk7nWY5e05K22A"
                val qqIntent = Intent(
                    Intent.ACTION_VIEW,
                    "mqqopensdkapi://bizAgent/qm/qr?url=http%3A%2F%2Fqm.qq.com%2Fcgi-bin%2Fqm%2Fqr%3Ffrom%3Dapp%26p%3Dandroid%26jump_from%3Dwebapi%26k%3D$key".toUri()
                )
                context.startActivity(qqIntent)
            } catch (_: Exception) {
                val browserIntent = Intent(
                    Intent.ACTION_VIEW,
                    "https://qm.qq.com/cgi-bin/qm/qr?k=NZXI7sTKBUJewXFD0O5iRtHrtN2sHaOs&jump_from=webapi&authKey=1jOita0a4w2N4x71MSV86VfiU3xhyZibVJ4SCgoIaGtTtO+jkCW9He9VzNfJeWz4".toUri()
                )
                context.startActivity(browserIntent)
            }
        }
    }

    // 更新弹窗
    updateInfo?.let { info ->
        UpdateDialog(
            updateInfo = info,
            onDismiss = { updateInfo = null },
            onSkipVersion = {
                MusicApplication.instance?.updateChecker?.skipVersion(info.title)
                updateInfo = null
            },
            onExitApp = { (context as? android.app.Activity)?.finish() },
            onDownload = {
                scope.launch {
                    downloadProgress = 0f
                    downloadFailed = false
                    withContext(Dispatchers.IO) {
                        val app = MusicApplication.instance ?: return@withContext
                        val file = app.updateChecker.downloadApk(
                            info, context.cacheDir
                        ) { downloadProgress = it }
                        if (file != null) {
                            withContext(Dispatchers.Main) {
                                (context as? com.ikunshare.music.MainActivity)?.installApk(file)
                            }
                        } else {
                            downloadFailed = true
                        }
                    }
                }
            },
            onBrowserDownload = {
                context.startActivity(Intent(Intent.ACTION_VIEW, info.shareUrl.toUri()))
                if (!info.isCompulsory) updateInfo = null
            },
            downloadProgress = downloadProgress,
            downloadFailed = downloadFailed
        )
    }

    noticeContent?.let { info ->
        NoticeDialog(
            content = info.content,
            onDismiss = { noticeContent = null }
        )
    }

    Spacer(Modifier.height(8.dp))

    // ── 开源与协议 ──
    var showEulaDialog by remember { mutableStateOf(false) }
    val eulaContent = remember {
        try {
            context.assets.open("eula.md").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            ""
        }
    }

    SettingClickableItem(
        label = "开源仓库",
        value = "ikunshare/kunyin · MIT License",
        icon = Icons.Filled.Code,
        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, REPO_URL.toUri())) }
    )
    if (eulaContent.isNotBlank()) {
        SettingClickableItem(
            label = "许可协议",
            value = "您已签署本软件的许可协议，点击再次查看",
            icon = Icons.Filled.Description,
            onClick = { showEulaDialog = true }
        )
    }

    if (showEulaDialog) {
        NoticeDialog(
            content = eulaContent,
            onDismiss = { showEulaDialog = false }
        )
    }

    // ── 软件开发 ──
    AboutGroupTitle("软件开发")
    Column(Modifier.fillMaxWidth().liquidGlass(RoundedRectangle(22.dp))) {
        AboutTeamRow(
            logo = R.drawable.ic_mpdl,
            name = "MPDL-Official",
            desc = "由 Naiy_ / lerd / ikun0014 等人组成"
        )
        HorizontalDivider(
            modifier = Modifier.padding(start = 64.dp, end = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        )
        AboutTeamRow(
            logo = R.drawable.ic_ikunshare,
            name = "ikun分享",
            desc = "还是由 Naiy_ / lerd / ikun0014 等人组成"
        )
    }

    Spacer(Modifier.height(24.dp))
    Text(
        "Copyright © 2026 ikunshare",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(16.dp))

    // ── 调试设置弹窗 ──
    if (showDebugScreen) {
        DebugSettingsDialog(onDismiss = { showDebugScreen = false })
    }
}

/** 顶部名片：仿启动器样式的图标 + 应用名 + 版本信息胶囊 */
@Composable
private fun AboutHeroCard(
    versionName: String,
    versionCode: Int,
    commitSha: String,
    onIconClick: () -> Unit,
    onVersionClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .liquidGlass(RoundedRectangle(28.dp))
            .background(
                Brush.verticalGradient(
                    listOf(colors.tertiary.copy(alpha = 0.16f), colors.tertiary.copy(alpha = 0f))
                ),
                RoundedCornerShape(28.dp)
            )
            .padding(horizontal = 20.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 自适应图标的两层画布是 108dp，可见区域为中间 72dp，放大 1.5 倍再裁切即可还原启动器效果
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(RoundedRectangle(26.dp))
                .clickable(onClick = onIconClick)
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_background),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().scale(1.5f)
            )
            Image(
                painter = painterResource(R.mipmap.ic_launcher_foreground),
                contentDescription = stringResource(R.string.app_icon),
                modifier = Modifier.fillMaxSize().scale(1.5f)
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "聚合多平台音源的 Android 音乐播放器",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AboutChip("v$versionName", highlighted = true, onClick = onVersionClick)
            AboutChip("Build $versionCode")
            AboutChip(commitSha)
        }
    }
}

@Composable
private fun AboutChip(
    text: String,
    highlighted: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (highlighted) colors.tertiary else colors.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(
                if (highlighted) colors.tertiary.copy(alpha = 0.14f)
                else colors.onSurface.copy(alpha = 0.06f)
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
private fun AboutActionTile(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .liquidGlass(RoundedRectangle(22.dp))
            .clickable(enabled = !loading, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(colors.tertiary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = colors.tertiary
                )
            } else {
                Icon(icon, contentDescription = null, tint = colors.tertiary, modifier = Modifier.size(22.dp))
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AboutGroupTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp)
    )
}

@Composable
private fun AboutTeamRow(logo: Int, name: String, desc: String) {
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(logo),
            contentDescription = null,
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
