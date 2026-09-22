package com.ikunshare.sound.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ikunshare.sound.R
import com.ikunshare.sound.common.AppSettings

/**
 * 字体设置页：统一自定义软件内所有字体（全局字号缩放 / 全局字重），
 * 以及歌词专属字号与字重。
 */
@Composable
internal fun FontSection(
    settings: AppSettings,
    vm: SettingsViewModel
) {
    SectionHeader(stringResource(R.string.section_font))

    // ── 全局字体 ──
    val scalePct = (settings.globalFontScale * 100).toInt()
    Text(
        stringResource(R.string.global_font_scale, scalePct),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(top = 8.dp)
    )
    Slider(
        value = settings.globalFontScale,
        onValueChange = { vm.updateSettings { copy(globalFontScale = it) } },
        valueRange = 0.8f..1.4f,
        steps = 11, // 每 0.05 一档
        modifier = Modifier.fillMaxWidth(),
        colors = SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.tertiary,
            activeTrackColor = MaterialTheme.colorScheme.tertiary
        )
    )

    FontDropdownRow(
        label = stringResource(R.string.global_font_weight),
        currentValue = settings.globalFontWeightAdjust.toString(),
        options = listOf(
            "-100" to stringResource(R.string.font_weight_light),
            "0" to stringResource(R.string.font_weight_regular),
            "100" to stringResource(R.string.font_weight_bold)
        )
    ) { vm.updateSettings { copy(globalFontWeightAdjust = it.toIntOrNull() ?: 0) } }

    Spacer(Modifier.height(12.dp))

    // ── 歌词字体 ──
    Text(
        stringResource(R.string.font_lyrics_section),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(bottom = 4.dp)
    )

    Text(
        stringResource(R.string.lyrics_font_size, settings.lyricsMainFontSize),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(top = 4.dp)
    )
    Slider(
        value = settings.lyricsMainFontSize.toFloat(),
        onValueChange = { vm.updateSettings { copy(lyricsMainFontSize = it.toInt()) } },
        valueRange = 14f..40f,
        steps = 25,
        modifier = Modifier.fillMaxWidth(),
        colors = SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.tertiary,
            activeTrackColor = MaterialTheme.colorScheme.tertiary
        )
    )

    Text(
        stringResource(R.string.lyrics_trans_font_size, settings.lyricsTransFontSize),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(top = 4.dp)
    )
    Slider(
        value = settings.lyricsTransFontSize.toFloat(),
        onValueChange = { vm.updateSettings { copy(lyricsTransFontSize = it.toInt()) } },
        valueRange = 10f..30f,
        steps = 19,
        modifier = Modifier.fillMaxWidth(),
        colors = SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.tertiary,
            activeTrackColor = MaterialTheme.colorScheme.tertiary
        )
    )

    FontDropdownRow(
        label = stringResource(R.string.lyrics_font_weight),
        currentValue = settings.lyricsFontWeight,
        options = listOf(
            "normal" to stringResource(R.string.lyrics_weight_normal),
            "medium" to stringResource(R.string.lyrics_weight_medium),
            "semibold" to stringResource(R.string.lyrics_weight_semibold),
            "bold" to stringResource(R.string.lyrics_weight_bold),
            "black" to stringResource(R.string.lyrics_weight_black)
        )
    ) { vm.updateSettings { copy(lyricsFontWeight = it) } }
}

/** 字体设置页专用的下拉选择行，风格与其它设置项一致。 */
@Composable
private fun FontDropdownRow(
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
            .heightIn(min = 48.dp)
            .padding(vertical = 8.dp)
            .clickable { expanded = true },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
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
                options.forEach { (value, name) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                name,
                                color = if (value == currentValue) MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.onBackground
                            )
                        },
                        onClick = { onSelect(value); expanded = false }
                    )
                }
            }
        }
    }
}
