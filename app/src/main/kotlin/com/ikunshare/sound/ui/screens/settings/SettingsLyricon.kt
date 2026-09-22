package com.ikunshare.sound.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
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

private val subLineOptions = listOf(
    "none" to R.string.sub_line_none,
    "translation" to R.string.sub_line_translation,
    "romanization" to R.string.sub_line_romanization
)

@Composable
internal fun LyriconSection(
    settings: com.ikunshare.sound.common.AppSettings,
    vm: SettingsViewModel
) {
    SectionHeader(stringResource(R.string.section_lyricon))

    SettingSwitchItem(
        label = stringResource(R.string.push_lyrics_to_media_session),
        checked = settings.pushLyricsToMediaSession,
        onCheckedChange = { enabled ->
            vm.updateSettings { copy(pushLyricsToMediaSession = enabled) }
        }
    )

    Text(
        text = stringResource(R.string.push_lyrics_to_media_session_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )

    SettingSwitchItem(
        label = stringResource(R.string.push_meizu_status_bar_lyrics),
        checked = settings.pushMeizuStatusBarLyrics,
        onCheckedChange = { enabled ->
            vm.updateSettings { copy(pushMeizuStatusBarLyrics = enabled) }
        }
    )

    Text(
        text = stringResource(R.string.push_meizu_status_bar_lyrics_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )

    SubLineSelector(
        label = stringResource(R.string.push_lyrics_sub_line),
        current = settings.pushLyricsSubLine,
        onSelect = { vm.updateSettings { copy(pushLyricsSubLine = it) } }
    )

    Text(
        text = stringResource(R.string.push_lyrics_sub_line_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )

    SettingSwitchItem(
        label = stringResource(R.string.lyricon_enable),
        checked = settings.lyriconEnabled,
        onCheckedChange = { enabled -> vm.updateLyriconEnabled(enabled) }
    )

    Text(
        text = stringResource(R.string.lyricon_enable_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )

    SubLineSelector(
        label = stringResource(R.string.lyricon_sub_line),
        current = settings.lyriconSubLine,
        onSelect = { vm.updateSettings { copy(lyriconSubLine = it) } }
    )
}

@Composable
private fun SubLineSelector(label: String, current: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabelRes = subLineOptions.firstOrNull { it.first == current }?.second
        ?: subLineOptions[0].second
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
                subLineOptions.forEach { (value, labelRes) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(labelRes),
                                color = if (value == current) MaterialTheme.colorScheme.tertiary
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
