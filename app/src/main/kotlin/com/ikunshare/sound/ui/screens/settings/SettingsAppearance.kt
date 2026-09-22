package com.ikunshare.sound.ui.screens.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.os.LocaleListCompat
import coil3.compose.AsyncImage
import com.ikunshare.sound.R
import com.ikunshare.sound.SoundApplication
import com.ikunshare.sound.ui.theme.CustomThemeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

internal val themeModeBuiltinOptions = listOf(
    "system" to R.string.theme_system,
    "dark" to R.string.theme_dark,
    "light" to R.string.theme_light
)

internal val languageOptions = listOf(
    "system" to R.string.language_system,
    "zh-CN" to R.string.language_zh_cn,
    "zh-TW" to R.string.language_zh_tw
)

@Composable
internal fun AppearanceSection(
    settings: com.ikunshare.sound.common.AppSettings,
    vm: SettingsViewModel
) {
    val context = LocalContext.current
    val appSettingsManager = SoundApplication.instance!!.appSettingsManager
    val customThemes by appSettingsManager.customThemes.collectAsState()
    var showColorEditor by remember { mutableStateOf(false) }
    var showBgDialog by remember { mutableStateOf(false) }
    var showNewThemeDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<String?>(null) }
    var showImportThemeDialog by remember { mutableStateOf(false) }
    var isShareBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    SectionHeader(stringResource(R.string.section_appearance))

    // 主题模式选择（含自定义主题列表和新建入口）
    ThemeModeSelector(
        currentMode = settings.themeMode,
        customThemes = customThemes,
        onSelect = { mode -> vm.updateSettings { copy(themeMode = mode) } },
        onCreateNew = { showNewThemeDialog = true }
    )

    // 跟随系统时：浅色/深色主题子选择
    if (settings.themeMode == "system") {
        // 构建可选列表: 内置 + 自定义
        val lightOptions = mutableListOf("light" to stringResource(R.string.theme_light))
        val darkOptions = mutableListOf("dark" to stringResource(R.string.theme_dark))
        customThemes.forEach { t ->
            val pair = "custom_${t.id}" to t.name
            lightOptions.add(pair)
            darkOptions.add(pair)
        }

        SystemThemeSubSelector(
            stringResource(R.string.system_light_theme),
            settings.systemLightTheme,
            lightOptions
        ) {
            vm.updateSettings { copy(systemLightTheme = it) }
        }
        SystemThemeSubSelector(
            stringResource(R.string.system_dark_theme),
            settings.systemDarkTheme,
            darkOptions
        ) {
            vm.updateSettings { copy(systemDarkTheme = it) }
        }
    }

    // 自定义主题选中时：编辑/重命名/删除/背景图
    val activeCustomId = if (settings.themeMode.startsWith("custom_"))
        settings.themeMode.removePrefix("custom_") else null
    val activeCustomTheme = activeCustomId?.let { id -> customThemes.firstOrNull { it.id == id } }

    if (activeCustomTheme != null) {
        // 编辑颜色
        SettingClickableItem(
            label = stringResource(R.string.edit_colors),
            value = stringResource(R.string.click_to_edit),
            valueColor = MaterialTheme.colorScheme.tertiary,
            onClick = { showColorEditor = true }
        )

        // 背景图（该自定义主题专属）
        SettingClickableItem(
            label = stringResource(R.string.custom_bg_image),
            value = if (activeCustomTheme.bgImagePath.isNotEmpty()) stringResource(R.string.bg_set)
            else stringResource(R.string.bg_not_set),
            valueColor = if (activeCustomTheme.bgImagePath.isNotEmpty()) MaterialTheme.colorScheme.tertiary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = { showBgDialog = true }
        )

        // 重命名 / 分享 / 删除
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { showRenameDialog = activeCustomId },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.rename_theme))
            }
            OutlinedButton(
                onClick = {
                    if (!isShareBusy) {
                        isShareBusy = true
                        val theme = activeCustomTheme
                        scope.launch(Dispatchers.IO) {
                            val result = ThemeShareHelper.exportTheme(theme)
                            launch(Dispatchers.Main) {
                                isShareBusy = false
                                if (result.shareCode != null) {
                                    val clip =
                                        context.getSystemService(android.content.ClipboardManager::class.java)
                                    clip?.setPrimaryClip(
                                        android.content.ClipData.newPlainText(
                                            "theme",
                                            result.shareCode
                                        )
                                    )
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.theme_share_copied),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    Toast.makeText(
                                        context,
                                        context.getString(
                                            R.string.theme_share_failed,
                                            result.error ?: ""
                                        ),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    }
                },
                enabled = !isShareBusy,
                modifier = Modifier.weight(1f)
            ) {
                if (isShareBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.share))
                }
            }
            OutlinedButton(
                onClick = { showDeleteConfirm = activeCustomId },
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    stringResource(R.string.delete),
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1
                )
            }
        }
    } else {
        // 内置主题: 共用背景图入口
        SettingClickableItem(
            label = stringResource(R.string.custom_bg_image),
            value = if (settings.bgImagePath.isNotEmpty()) stringResource(R.string.bg_set)
            else stringResource(R.string.bg_not_set),
            valueColor = if (settings.bgImagePath.isNotEmpty()) MaterialTheme.colorScheme.tertiary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = { showBgDialog = true }
        )
    }

    // 语言选择
    LanguageSelector(settings.language) { lang ->
        vm.updateSettings { copy(language = lang) }
        val locales = when (lang) {
            "zh-CN" -> LocaleListCompat.forLanguageTags("zh-CN")
            "zh-TW" -> LocaleListCompat.forLanguageTags("zh-TW")
            else -> LocaleListCompat.getEmptyLocaleList()
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    // 详情页封面背景开关
    SettingSwitchItem(stringResource(R.string.cover_blur_bg), settings.coverBlurBg) {
        vm.updateSettings { copy(coverBlurBg = it) }
    }

    // 悬浮底栏
    SettingSwitchItem(stringResource(R.string.floating_bottom_bar), settings.floatingBottomBar) {
        vm.updateSettings {
            if (it) copy(floatingBottomBar = true)
            else copy(floatingBottomBar = false, liquidGlassMode = false)
        }
    }

    // 液态玻璃（需要悬浮底栏 + Android 10+）
    if (settings.floatingBottomBar && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        SettingSwitchItem(stringResource(R.string.enable_liquid_glass), settings.liquidGlassMode) {
            vm.updateSettings { copy(liquidGlassMode = it) }
        }
    }

    // 导入主题
    SettingClickableItem(
        label = stringResource(R.string.import_theme),
        value = stringResource(R.string.import_theme_hint),
        onClick = { showImportThemeDialog = true }
    )

    // ── 对话框 ──

    // 颜色编辑器（自定义主题）
    if (showColorEditor && activeCustomId != null) {
        val entry = activeCustomTheme ?: return
        val currentConfig = CustomThemeColors.fromJson(entry.colorsJson) ?: CustomThemeColors()
        ThemeColorEditor(
            initialConfig = currentConfig,
            onDismiss = { showColorEditor = false },
            onSave = { config ->
                appSettingsManager.updateCustomTheme(activeCustomId) { copy(colorsJson = config.toJson()) }
                showColorEditor = false
            }
        )
    }

    // 背景设置对话框
    if (showBgDialog) {
        if (activeCustomId != null) {
            // 自定义主题: 独立背景图，无 overlay/底栏滑条
            CustomThemeBgDialog(
                bgImagePath = activeCustomTheme?.bgImagePath ?: "",
                onBgChanged = { path ->
                    appSettingsManager.updateCustomTheme(activeCustomId) { copy(bgImagePath = path) }
                },
                onDismiss = { showBgDialog = false }
            )
        } else {
            // 内置主题: 共用背景图，有 overlay + 底栏滑条
            BgSettingsDialog(settings = settings, vm = vm, onDismiss = { showBgDialog = false })
        }
    }

    // 新建自定义主题对话框
    if (showNewThemeDialog) {
        TextInputDialog(
            title = stringResource(R.string.new_custom_theme),
            hint = stringResource(R.string.theme_name_hint),
            onConfirm = { name ->
                val id = appSettingsManager.addCustomTheme(name)
                vm.updateSettings { copy(themeMode = "custom_$id") }
                showNewThemeDialog = false
            },
            onDismiss = { showNewThemeDialog = false }
        )
    }

    // 重命名对话框
    showRenameDialog?.let { themeId ->
        val theme = customThemes.firstOrNull { it.id == themeId }
        TextInputDialog(
            title = stringResource(R.string.rename_theme),
            hint = stringResource(R.string.theme_name_hint),
            initialValue = theme?.name ?: "",
            onConfirm = { name ->
                appSettingsManager.updateCustomTheme(themeId) { copy(name = name) }
                showRenameDialog = null
            },
            onDismiss = { showRenameDialog = null }
        )
    }

    // 删除确认
    showDeleteConfirm?.let { themeId ->
        val theme = customThemes.firstOrNull { it.id == themeId }
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text(stringResource(R.string.delete_theme)) },
            text = { Text(stringResource(R.string.delete_theme_confirm, theme?.name ?: "")) },
            confirmButton = {
                TextButton(onClick = {
                    appSettingsManager.deleteCustomTheme(themeId)
                    showDeleteConfirm = null
                }) {
                    Text(
                        stringResource(R.string.delete_theme),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) {
                    Text(
                        stringResource(android.R.string.cancel),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        )
    }

    // 导入主题对话框
    if (showImportThemeDialog) {
        var codeInput by remember { mutableStateOf("") }
        var importing by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!importing) showImportThemeDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Text(
                    stringResource(R.string.import_theme),
                    color = MaterialTheme.colorScheme.onBackground
                )
            },
            text = {
                Column {
                    Text(
                        stringResource(R.string.import_theme_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = codeInput,
                        onValueChange = { codeInput = it },
                        label = { Text(stringResource(R.string.import_theme_label)) },
                        singleLine = true,
                        enabled = !importing,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (importing) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.importing),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        importing = true
                        val input = codeInput.trim()
                        scope.launch(Dispatchers.IO) {
                            val parsed = ThemeShareHelper.parseShareCode(input)
                            if (parsed == null || CustomThemeColors.fromJson(parsed.colorsJson) == null) {
                                launch(Dispatchers.Main) {
                                    importing = false
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.import_theme_invalid),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                return@launch
                            }
                            val name =
                                parsed.name.ifBlank { context.getString(R.string.imported_theme_name) }
                            val newId = appSettingsManager.addCustomTheme(name, parsed.colorsJson)
                            // 下载背景图
                            if (parsed.bgUrl.isNotBlank()) {
                                try {
                                    val resp = com.ikunshare.sound.utils.HTTPUtils.get(parsed.bgUrl)
                                    resp.bodyBytes?.let { bytes ->
                                        val ext = parsed.bgExt.ifBlank { "jpg" }
                                        val dest = File(
                                            context.filesDir,
                                            "custom_bg_${System.currentTimeMillis()}.$ext"
                                        )
                                        dest.writeBytes(bytes)
                                        appSettingsManager.updateCustomTheme(newId) {
                                            copy(
                                                bgImagePath = dest.absolutePath
                                            )
                                        }
                                    }
                                } catch (_: Exception) { /* skip bg */
                                }
                            }
                            launch(Dispatchers.Main) {
                                importing = false
                                showImportThemeDialog = false
                                vm.updateSettings { copy(themeMode = "custom_$newId") }
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.import_theme_success),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    },
                    enabled = codeInput.isNotBlank() && !importing
                ) {
                    Text(
                        stringResource(R.string.import_text),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportThemeDialog = false }, enabled = !importing) {
                    Text(
                        stringResource(R.string.cancel),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        )
    }
}

@Composable
internal fun ThemeModeSelector(
    currentMode: String,
    customThemes: List<com.ikunshare.sound.common.CustomThemeEntry>,
    onSelect: (String) -> Unit,
    onCreateNew: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    // 显示名
    val currentLabel = when {
        currentMode == "system" -> stringResource(R.string.theme_system)
        currentMode == "dark" -> stringResource(R.string.theme_dark)
        currentMode == "light" -> stringResource(R.string.theme_light)
        currentMode.startsWith("custom_") -> {
            val id = currentMode.removePrefix("custom_")
            customThemes.firstOrNull { it.id == id }?.name ?: currentMode
        }

        else -> stringResource(R.string.theme_system)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 8.dp)
            .clickable { expanded = true },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            stringResource(R.string.theme_mode),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Box {
            Text(
                currentLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                // 内置选项
                themeModeBuiltinOptions.forEach { (mode, labelRes) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(labelRes),
                                color = if (mode == currentMode) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onBackground
                            )
                        },
                        onClick = { onSelect(mode); expanded = false }
                    )
                }
                // 自定义主题
                customThemes.forEach { theme ->
                    val themeId = "custom_${theme.id}"
                    DropdownMenuItem(
                        text = {
                            Text(
                                theme.name,
                                color = if (themeId == currentMode) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onBackground
                            )
                        },
                        onClick = { onSelect(themeId); expanded = false }
                    )
                }
                // 新建
                DropdownMenuItem(
                    text = {
                        Text(
                            "+ ${stringResource(R.string.new_custom_theme)}",
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    },
                    onClick = { expanded = false; onCreateNew() }
                )
            }
        }
    }
}

@Composable
internal fun SystemThemeSubSelector(
    label: String,
    currentValue: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = options.firstOrNull { it.first == currentValue }?.second ?: currentValue

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
            .clickable { expanded = true },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box {
            Text(
                currentLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, name) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                name,
                                color = if (value == currentValue) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onBackground
                            )
                        },
                        onClick = { onSelect(value); expanded = false }
                    )
                }
            }
        }
    }
}

@Composable
internal fun TextInputDialog(
    title: String,
    hint: String,
    initialValue: String = "",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(hint) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) {
                Text(
                    stringResource(android.R.string.ok),
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(android.R.string.cancel),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    )
}

@Composable
internal fun CustomThemeBgDialog(
    bgImagePath: String,
    onBgChanged: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val destFile =
                    File(context.filesDir, "custom_bg_${System.currentTimeMillis()}.jpg")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
                onBgChanged(destFile.absolutePath)
            } catch (_: Exception) {
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(DialogCornerRadius),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    stringResource(R.string.custom_bg_image),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(16.dp))

                if (bgImagePath.isNotEmpty()) {
                    AsyncImage(
                        model = File(bgImagePath),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                stringResource(R.string.no_bg_image),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { imagePicker.launch("image/*") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.select_image))
                    }
                    if (bgImagePath.isNotEmpty()) {
                        OutlinedButton(onClick = {
                            try {
                                File(bgImagePath).delete()
                            } catch (_: Exception) {
                            }
                            onBgChanged("")
                        }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.clear_image))
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            stringResource(R.string.close),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun LanguageSelector(currentLang: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabelRes = languageOptions.firstOrNull { it.first == currentLang }?.second
        ?: languageOptions[0].second
    val currentLabel = stringResource(currentLabelRes)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 8.dp)
            .clickable { expanded = true },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            stringResource(R.string.language_label),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Box {
            Text(
                currentLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                languageOptions.forEach { (lang, labelRes) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(labelRes),
                                color = if (lang == currentLang) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onBackground
                            )
                        },
                        onClick = { onSelect(lang); expanded = false }
                    )
                }
            }
        }
    }
}

@Composable
internal fun BgSettingsDialog(
    settings: com.ikunshare.sound.common.AppSettings,
    vm: SettingsViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            // 复制图片到内部存储
            try {
                val destFile = File(context.filesDir, "custom_bg.jpg")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                vm.updateSettings { copy(bgImagePath = destFile.absolutePath) }
            } catch (_: Exception) {
                // 复制失败，忽略
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(DialogCornerRadius),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    stringResource(R.string.custom_bg_image),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))

                // 预览
                if (settings.bgImagePath.isNotEmpty()) {
                    AsyncImage(
                        model = File(settings.bgImagePath),
                        contentDescription = stringResource(R.string.bg_preview),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                stringResource(R.string.no_bg_image),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 按钮行
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { imagePicker.launch("image/*") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.select_image))
                    }
                    if (settings.bgImagePath.isNotEmpty()) {
                        OutlinedButton(
                            onClick = {
                                try {
                                    File(settings.bgImagePath).delete()
                                } catch (_: Exception) {
                                }
                                vm.updateSettings {
                                    copy(
                                        bgImagePath = "",
                                        bgImageOnPlayer = false
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.clear_image))
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // 在播放详情页使用
                SettingSwitchItem(
                    stringResource(R.string.use_on_player),
                    settings.bgImageOnPlayer
                ) {
                    vm.updateSettings { copy(bgImageOnPlayer = it) }
                }

                // 背景色不透明度（内置主题有图片时）
                if (settings.bgImagePath.isNotEmpty()) {
                    Text(
                        stringResource(
                            R.string.bg_opacity,
                            (settings.bgOverlayOpacity * 100).toInt()
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Slider(
                        value = settings.bgOverlayOpacity,
                        onValueChange = { vm.updateSettings { copy(bgOverlayOpacity = it) } },
                        valueRange = 0f..1f,
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.tertiary,
                            activeTrackColor = MaterialTheme.colorScheme.tertiary
                        )
                    )
                }

                // 底栏不透明度（有图片时）
                if (settings.bgImagePath.isNotEmpty()) {
                    Text(
                        stringResource(
                            R.string.bottom_bar_opacity,
                            (settings.bottomBarOpacity * 100).toInt()
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Slider(
                        value = settings.bottomBarOpacity,
                        onValueChange = { vm.updateSettings { copy(bottomBarOpacity = it) } },
                        valueRange = 0f..1f,
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.tertiary,
                            activeTrackColor = MaterialTheme.colorScheme.tertiary
                        )
                    )
                }

                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            stringResource(R.string.close),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }
        }
    }
}
