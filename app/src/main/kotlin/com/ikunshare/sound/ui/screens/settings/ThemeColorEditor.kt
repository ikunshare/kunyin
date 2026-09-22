package com.ikunshare.sound.ui.screens.settings

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.ikunshare.sound.R
import com.ikunshare.sound.ui.components.ColorPickerDialog
import com.ikunshare.sound.ui.components.toArgbLong
import com.ikunshare.sound.ui.theme.CustomThemeColors

/**
 * 主题颜色编辑器对话框
 */
@Composable
fun ThemeColorEditor(
    initialConfig: CustomThemeColors,
    onDismiss: () -> Unit,
    onSave: (CustomThemeColors) -> Unit
) {
    var config by remember { mutableStateOf(initialConfig) }
    var colorPickerTarget by remember { mutableStateOf<String?>(null) }
    var colorPickerInitial by remember { mutableStateOf(Color.White) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    stringResource(R.string.custom_color),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(16.dp))

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    SectionLabel(stringResource(R.string.base_colors))
                    ColorRow(stringResource(R.string.color_primary), config.primary) {
                        colorPickerTarget = "primary"
                        colorPickerInitial = Color(config.primary)
                    }
                    ColorRow(stringResource(R.string.color_secondary), config.secondary) {
                        colorPickerTarget = "secondary"
                        colorPickerInitial = Color(config.secondary)
                    }
                    ColorRow(stringResource(R.string.color_accent), config.tertiary) {
                        colorPickerTarget = "tertiary"
                        colorPickerInitial = Color(config.tertiary)
                    }
                    ColorRow(stringResource(R.string.color_background), config.background) {
                        colorPickerTarget = "background"
                        colorPickerInitial = Color(config.background)
                    }
                    ColorRow(stringResource(R.string.color_surface), config.surface) {
                        colorPickerTarget = "surface"
                        colorPickerInitial = Color(config.surface)
                    }
                    ColorRow(
                        stringResource(R.string.color_surface_variant),
                        config.surfaceVariant
                    ) {
                        colorPickerTarget = "surfaceVariant"
                        colorPickerInitial = Color(config.surfaceVariant)
                    }
                    ColorRow(stringResource(R.string.color_on_background), config.onBackground) {
                        colorPickerTarget = "onBackground"
                        colorPickerInitial = Color(config.onBackground)
                    }
                    ColorRow(
                        stringResource(R.string.color_on_surface_variant),
                        config.onSurfaceVariant
                    ) {
                        colorPickerTarget = "onSurfaceVariant"
                        colorPickerInitial = Color(config.onSurfaceVariant)
                    }
                    ColorRow(stringResource(R.string.color_error), config.error) {
                        colorPickerTarget = "error"
                        colorPickerInitial = Color(config.error)
                    }
                    ColorRow(stringResource(R.string.color_outline), config.outline) {
                        colorPickerTarget = "outline"
                        colorPickerInitial = Color(config.outline)
                    }

                    Spacer(Modifier.height(16.dp))
                    SectionLabel(stringResource(R.string.ui_colors))
                    ColorRow(
                        stringResource(R.string.selection_highlight),
                        config.selectionHighlight
                    ) {
                        colorPickerTarget = "selectionHighlight"
                        colorPickerInitial = Color(config.selectionHighlight)
                    }
                    ColorRow(
                        stringResource(R.string.bottom_bar_search_bar),
                        config.bottomBarColor
                    ) {
                        colorPickerTarget = "bottomBarColor"
                        colorPickerInitial = Color(config.bottomBarColor)
                    }
                    ColorRow(stringResource(R.string.song_card), config.songCardColor) {
                        colorPickerTarget = "songCardColor"
                        colorPickerInitial = Color(config.songCardColor)
                    }
                    ColorRow(stringResource(R.string.dialog_background), config.dialogBackground) {
                        colorPickerTarget = "dialogBackground"
                        colorPickerInitial = Color(config.dialogBackground)
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { config = CustomThemeColors() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.reset_to_default))
                    }
                    Button(
                        onClick = { onSave(config) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary
                        )
                    ) {
                        Text(stringResource(R.string.save))
                    }
                }
            }
        }
    }

    // 颜色选择器弹窗
    colorPickerTarget?.let { target ->
        ColorPickerDialog(
            initialColor = colorPickerInitial,
            onDismiss = { colorPickerTarget = null },
            onConfirm = { color ->
                val argbLong = color.toArgbLong()
                config = when (target) {
                    "primary" -> config.copy(primary = argbLong)
                    "secondary" -> config.copy(secondary = argbLong)
                    "tertiary" -> config.copy(tertiary = argbLong)
                    "background" -> config.copy(background = argbLong)
                    "surface" -> config.copy(surface = argbLong)
                    "surfaceVariant" -> config.copy(surfaceVariant = argbLong)
                    "onBackground" -> config.copy(onBackground = argbLong)
                    "onSurfaceVariant" -> config.copy(onSurfaceVariant = argbLong)
                    "error" -> config.copy(error = argbLong)
                    "outline" -> config.copy(outline = argbLong)
                    "selectionHighlight" -> config.copy(selectionHighlight = argbLong)
                    "bottomBarColor" -> config.copy(bottomBarColor = argbLong)
                    "songCardColor" -> config.copy(songCardColor = argbLong)
                    "dialogBackground" -> config.copy(dialogBackground = argbLong)
                    else -> config
                }
                colorPickerTarget = null
            }
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun ColorRow(
    label: String,
    colorLong: Long,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "#%08X".format(colorLong),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Color(colorLong))
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
        )
    }
}
