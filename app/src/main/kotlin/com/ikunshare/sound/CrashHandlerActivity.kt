package com.ikunshare.sound

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.ikunshare.sound.common.CustomThemeEntry
import com.ikunshare.sound.ui.theme.KunSoundTheme
import com.ikunshare.sound.ui.theme.resolveActiveThemeId
import org.json.JSONArray
import java.io.File
import kotlin.system.exitProcess

class CrashHandlerActivity : ComponentActivity() {

    private lateinit var crashLog: String

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        crashLog = intent?.getStringExtra("crash_log") ?: "No crash log available"

        // 从 SharedPreferences 读取主题配置（跨进程读取 XML 文件）
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        val themeMode = prefs.getString("theme_mode", "system") ?: "system"
        val systemLightTheme = prefs.getString("system_light_theme", "light") ?: "light"
        val systemDarkTheme = prefs.getString("system_dark_theme", "dark") ?: "dark"
        val customThemes = loadCustomThemes(prefs.getString("custom_themes", null))
        val bgImagePath = prefs.getString("bg_image_path", "") ?: ""
        val bgOverlayOpacity = prefs.getFloat("bg_overlay_opacity", 0.7f)

        setContent {
            KunSoundTheme(
                themeMode = themeMode,
                customThemes = customThemes,
                systemLightTheme = systemLightTheme,
                systemDarkTheme = systemDarkTheme
            ) {
                // 解析当前生效主题，确定背景图路径
                val isSystemDark = isSystemInDarkTheme()
                val activeThemeId = resolveActiveThemeId(
                    themeMode, systemLightTheme, systemDarkTheme, isSystemDark
                )
                val isCustomTheme = activeThemeId.startsWith("custom_")
                val activeBgImagePath = if (isCustomTheme) {
                    val id = activeThemeId.removePrefix("custom_")
                    customThemes.firstOrNull { it.id == id }?.bgImagePath ?: ""
                } else {
                    bgImagePath
                }

                CrashScreen(
                    crashLog = crashLog,
                    bgImagePath = activeBgImagePath,
                    bgOverlayOpacity = bgOverlayOpacity,
                    isCustomTheme = isCustomTheme,
                    onCopy = ::copyCrashLog,
                    onShare = ::shareCrashLog,
                    onRestart = ::restartApp,
                    onExit = ::exitApp
                )
            }
        }
    }

    private fun loadCustomThemes(json: String?): List<CustomThemeEntry> {
        if (json == null) return emptyList()
        return try {
            val arr = JSONArray(json)
            List(arr.length()) { i ->
                val obj = arr.getJSONObject(i)
                CustomThemeEntry(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    colorsJson = obj.getString("colorsJson"),
                    bgImagePath = obj.optString("bgImagePath", "")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun copyCrashLog() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Crash Log", crashLog))
        Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun shareCrashLog() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Kunyin Crash Log")
            putExtra(Intent.EXTRA_TEXT, crashLog)
        }
        startActivity(Intent.createChooser(intent, "分享崩溃日志"))
    }

    private fun restartApp() {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        if (launchIntent != null) {
            startActivity(launchIntent)
        }
        finishAffinity()
        Process.killProcess(Process.myPid())
    }

    private fun exitApp() {
        finishAffinity()
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CrashScreen(
    crashLog: String,
    bgImagePath: String,
    bgOverlayOpacity: Float,
    isCustomTheme: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onRestart: () -> Unit,
    onExit: () -> Unit
) {
    // 提取异常摘要（第一行有意义的异常信息）
    val summary = crashLog.lineSequence()
        .firstOrNull { it.contains("Exception") || it.contains("Error") }
        ?.trim()
        ?: "Unknown error"

    val hasBgImage = bgImagePath.isNotEmpty()

    Box(Modifier.fillMaxSize()) {
        // 全局背景图层（与 MainActivity 一致）
        if (hasBgImage) {
            val bgFile = remember(bgImagePath) { File(bgImagePath) }
            if (bgFile.exists()) {
                AsyncImage(
                    model = bgFile,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                // 叠加半透明背景色遮罩
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            if (isCustomTheme) {
                                MaterialTheme.colorScheme.background
                            } else {
                                MaterialTheme.colorScheme.background.copy(
                                    alpha = bgOverlayOpacity
                                )
                            }
                        )
                )
            }
        }

        Scaffold(
            containerColor = if (hasBgImage) Color.Transparent else MaterialTheme.colorScheme.background
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                // ── 标题区 ──
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "APP 发生崩溃",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3
                )

                Spacer(modifier = Modifier.height(20.dp))

                // ── 按钮区 ──
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CrashButton(
                        text = "复制日志",
                        icon = {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        onClick = onCopy,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    CrashButton(
                        text = "分享日志",
                        icon = {
                            Icon(
                                Icons.Default.Share,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        onClick = onShare,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    CrashButton(
                        text = "重启 APP",
                        icon = {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        onClick = onRestart,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    CrashButton(
                        text = "退出 APP",
                        icon = {
                            Icon(
                                Icons.AutoMirrored.Filled.ExitToApp,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        onClick = onExit,
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // ── 崩溃日志区 ──
                Text(
                    text = "崩溃日志",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                RoundedCornerShape(12.dp)
                            )
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp)
                    ) {
                        Text(
                            text = crashLog,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 16.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CrashButton(
    text: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    containerColor: Color,
    contentColor: Color
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        icon()
        Spacer(modifier = Modifier.width(6.dp))
        Text(text, fontSize = 13.sp)
    }
}
