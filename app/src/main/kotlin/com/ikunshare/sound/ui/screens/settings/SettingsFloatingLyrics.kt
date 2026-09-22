package com.ikunshare.sound.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ikunshare.sound.R
import com.ikunshare.sound.SoundApplication
import kotlin.math.roundToInt
import android.provider.Settings as AndroidSettings

internal val floatingLyricsAlignmentOptions = listOf(
    "left" to R.string.align_left,
    "center" to R.string.align_center,
    "right" to R.string.align_right
)

internal val floatingLyricsPresetColors = listOf(
    0xFF4CAF50.toInt() to R.string.color_green,
    0xFFFFFFFF.toInt() to R.string.color_white,
    0xFFFF5722.toInt() to R.string.color_orange_red,
    0xFF2196F3.toInt() to R.string.color_blue,
    0xFFFFEB3B.toInt() to R.string.color_yellow,
    0xFFE91E63.toInt() to R.string.color_pink,
    0xFF9C27B0.toInt() to R.string.color_purple,
    0xFF00BCD4.toInt() to R.string.color_cyan,
    0xFFF44336.toInt() to R.string.color_red,
    0xFF8BC34A.toInt() to R.string.color_light_green
)

@Composable
internal fun FloatingLyricsSection(
    settings: com.ikunshare.sound.common.AppSettings,
    vm: SettingsViewModel
) {
    val context = LocalContext.current

    SectionHeader(stringResource(R.string.section_floating_lyrics))

    // 启用开关（带权限检查）
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            stringResource(R.string.enable_floating_lyrics),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Switch(
            checked = settings.floatingLyricsEnabled,
            onCheckedChange = { enabled ->
                if (enabled) {
                    val mgr = SoundApplication.instance?.floatingLyricsManager
                    if (mgr?.hasOverlayPermission() != true) {
                        // 跳转系统权限设置页
                        val intent = Intent(
                            AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        return@Switch
                    }
                }
                vm.updateSettings { copy(floatingLyricsEnabled = enabled) }
            }
        )
    }

    // 以下仅在启用时显示
    if (settings.floatingLyricsEnabled) {
        SettingSwitchItem(
            stringResource(R.string.floating_karaoke),
            settings.floatingLyricsKaraoke
        ) {
            vm.updateSettings { copy(floatingLyricsKaraoke = it) }
        }
        SettingSwitchItem(
            stringResource(R.string.floating_translation),
            settings.floatingLyricsTranslation
        ) {
            vm.updateSettings { copy(floatingLyricsTranslation = it) }
        }
        SettingSwitchItem(
            stringResource(R.string.floating_romanization),
            settings.floatingLyricsRomanization
        ) {
            vm.updateSettings { copy(floatingLyricsRomanization = it) }
        }
        SettingSwitchItem(stringResource(R.string.lock_lyrics), settings.floatingLyricsLocked) {
            vm.updateSettings { copy(floatingLyricsLocked = it) }
        }
        Text(
            stringResource(R.string.lock_lyrics_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        SettingSwitchItem(
            stringResource(R.string.hide_when_not_playing),
            settings.floatingLyricsHideWhenNotPlaying
        ) {
            vm.updateSettings { copy(floatingLyricsHideWhenNotPlaying = it) }
        }

        // 对齐方式
        FloatingLyricsAlignmentSelector(settings.floatingLyricsAlignment) { align ->
            vm.updateSettings { copy(floatingLyricsAlignment = align) }
        }

        // 浮窗宽度
        val widthPercent = (settings.floatingLyricsWidthPercent * 100).toInt()
        Text(
            stringResource(R.string.floating_width, widthPercent),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 8.dp)
        )
        Slider(
            value = settings.floatingLyricsWidthPercent,
            onValueChange = { vm.updateSettings { copy(floatingLyricsWidthPercent = it) } },
            valueRange = 0.3f..1.0f,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.tertiary,
                activeTrackColor = MaterialTheme.colorScheme.tertiary
            )
        )

        val maxLines = settings.floatingLyricsMaxLines
        Text(
            stringResource(R.string.floating_max_lines, maxLines),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 8.dp)
        )
        Slider(
            value = maxLines.toFloat(),
            onValueChange = { value ->
                vm.updateSettings {
                    copy(floatingLyricsMaxLines = value.roundToInt().coerceIn(1, 6))
                }
            },
            valueRange = 1f..6f,
            steps = 4,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.tertiary,
                activeTrackColor = MaterialTheme.colorScheme.tertiary
            )
        )

        // 主歌词字号
        val mainFontSize = settings.floatingLyricsFontSize
        Text(
            stringResource(R.string.floating_lyrics_font_size, mainFontSize),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 8.dp)
        )
        Slider(
            value = mainFontSize.toFloat(),
            onValueChange = { vm.updateSettings { copy(floatingLyricsFontSize = it.roundToInt()) } },
            valueRange = 12f..32f,
            steps = 19,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.tertiary,
                activeTrackColor = MaterialTheme.colorScheme.tertiary
            )
        )

        // 翻译/音译字号
        val subFontSize = settings.floatingLyricsSubFontSize
        Text(
            stringResource(R.string.floating_lyrics_sub_font_size, subFontSize),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 4.dp)
        )
        Slider(
            value = subFontSize.toFloat(),
            onValueChange = { vm.updateSettings { copy(floatingLyricsSubFontSize = it.roundToInt()) } },
            valueRange = 10f..28f,
            steps = 17,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.tertiary,
                activeTrackColor = MaterialTheme.colorScheme.tertiary
            )
        )

        // 已播放颜色
        FloatingLyricsColorItem(
            stringResource(R.string.played_color),
            settings.floatingLyricsPlayedColor
        ) {
            vm.updateSettings { copy(floatingLyricsPlayedColor = it) }
        }
        // 未播放颜色
        FloatingLyricsColorItem(
            stringResource(R.string.unplayed_color),
            settings.floatingLyricsUnplayedColor
        ) {
            vm.updateSettings { copy(floatingLyricsUnplayedColor = it) }
        }
        // 翻译/音译颜色
        FloatingLyricsColorItem(
            stringResource(R.string.floating_sub_color),
            settings.floatingLyricsSubColor
        ) {
            vm.updateSettings { copy(floatingLyricsSubColor = it) }
        }

        // 窗口透明度
        val bgOpacityPercent = (settings.floatingLyricsBgOpacity * 100).roundToInt()
        Text(
            stringResource(R.string.floating_bg_opacity, bgOpacityPercent),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 8.dp)
        )
        Slider(
            value = settings.floatingLyricsBgOpacity,
            onValueChange = { value ->
                val quantized = (value * 100).roundToInt().coerceIn(0, 100) / 100f
                vm.updateSettings { copy(floatingLyricsBgOpacity = quantized) }
            },
            valueRange = 0f..1f,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.tertiary,
                activeTrackColor = MaterialTheme.colorScheme.tertiary
            )
        )
    }
}

@Composable
internal fun FloatingLyricsAlignmentSelector(currentAlign: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabelRes =
        floatingLyricsAlignmentOptions.firstOrNull { it.first == currentAlign }?.second
            ?: floatingLyricsAlignmentOptions[1].second
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
            stringResource(R.string.alignment),
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
                floatingLyricsAlignmentOptions.forEach { (align, labelRes) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(labelRes),
                                color = if (align == currentAlign) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onBackground
                            )
                        },
                        onClick = { onSelect(align); expanded = false }
                    )
                }
            }
        }
    }
}

@Composable
internal fun FloatingLyricsColorItem(
    label: String,
    currentColor: Int,
    onColorSelected: (Int) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 8.dp)
            .clickable { showDialog = true },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "#${Integer.toHexString(currentColor).uppercase().padStart(8, '0').takeLast(6)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp)
            )
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(
                        color = Color(currentColor),
                        shape = CircleShape
                    )
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            )
        }
    }

    if (showDialog) {
        FloatingLyricsColorPickerDialog(currentColor, onColorSelected) { showDialog = false }
    }
}

@Composable
internal fun FloatingLyricsColorPickerDialog(
    currentColor: Int,
    onColorSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var hexInput by remember {
        mutableStateOf(Integer.toHexString(currentColor).uppercase().padStart(8, '0').takeLast(6))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Text(
                stringResource(R.string.select_color),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        text = {
            Column {
                // 预设颜色网格
                Text(
                    stringResource(R.string.preset_colors),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                // 每行5个
                for (row in floatingLyricsPresetColors.chunked(5)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        row.forEach { (color, _) ->
                            val isSelected = color == currentColor
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(
                                        color = Color(color),
                                        shape = CircleShape
                                    )
                                    .then(
                                        if (isSelected) Modifier.border(
                                            2.dp, MaterialTheme.colorScheme.tertiary,
                                            CircleShape
                                        )
                                        else Modifier.border(
                                            1.dp, MaterialTheme.colorScheme.outline,
                                            CircleShape
                                        )
                                    )
                                    .clickable {
                                        onColorSelected(color)
                                        onDismiss()
                                    }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // 自定义 Hex 输入
                Text(
                    stringResource(R.string.custom_color_input),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "#",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = hexInput,
                        onValueChange = { v ->
                            val filtered = v.filter { it in "0123456789ABCDEFabcdef" }.take(6)
                            hexInput = filtered
                        },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 4.dp),
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.width(8.dp))
                    // 预览
                    val previewColor = try {
                        if (hexInput.length == 6) (0xFF000000 or hexInput.toLong(16)).toInt() else currentColor
                    } catch (_: Exception) {
                        currentColor
                    }
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(
                                color = Color(previewColor),
                                shape = CircleShape
                            )
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                try {
                    if (hexInput.length == 6) {
                        val color = (0xFF000000 or hexInput.toLong(16)).toInt()
                        onColorSelected(color)
                    }
                } catch (_: Exception) {
                }
                onDismiss()
            }) {
                Text(
                    stringResource(R.string.confirm),
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(R.string.cancel),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    )
}
