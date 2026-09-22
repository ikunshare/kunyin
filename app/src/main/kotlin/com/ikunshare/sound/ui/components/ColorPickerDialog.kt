package com.ikunshare.sound.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.ikunshare.sound.R
import android.graphics.Color as AndroidColor

/**
 * 颜色选择器对话框
 *
 * 提供色相条、饱和度/明度面板、透明度滑块、Hex 输入和预览。
 */
@Composable
fun ColorPickerDialog(
    initialColor: Color,
    onDismiss: () -> Unit,
    onConfirm: (Color) -> Unit
) {
    val initArgb = initialColor.toArgb()
    val initHsv = FloatArray(3)
    AndroidColor.colorToHSV(initArgb or 0xFF000000.toInt(), initHsv)
    val initAlpha = (initArgb ushr 24) / 255f

    var hue by remember { mutableFloatStateOf(initHsv[0]) }
    var saturation by remember { mutableFloatStateOf(initHsv[1]) }
    var value by remember { mutableFloatStateOf(initHsv[2]) }
    var alpha by remember { mutableFloatStateOf(initAlpha) }
    var hexInput by remember { mutableStateOf(colorToHex(initialColor)) }
    var hexError by remember { mutableStateOf(false) }

    fun currentColor(): Color {
        val rgb = AndroidColor.HSVToColor(floatArrayOf(hue, saturation, value))
        return Color(
            red = (rgb shr 16 and 0xFF) / 255f,
            green = (rgb shr 8 and 0xFF) / 255f,
            blue = (rgb and 0xFF) / 255f,
            alpha = alpha
        )
    }

    fun updateHex() {
        hexInput = colorToHex(currentColor())
        hexError = false
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    stringResource(R.string.select_color),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(16.dp))

                // SV 面板
                SaturationValuePanel(
                    hue = hue,
                    saturation = saturation,
                    value = value,
                    onSaturationValueChange = { s, v ->
                        saturation = s; value = v; updateHex()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(12.dp))
                )

                Spacer(Modifier.height(16.dp))

                // 色相条
                HueBar(
                    hue = hue,
                    onHueChange = { hue = it; updateHex() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                )

                Spacer(Modifier.height(12.dp))

                // 透明度条
                AlphaBar(
                    hue = hue,
                    saturation = saturation,
                    value = value,
                    alpha = alpha,
                    onAlphaChange = { alpha = it; updateHex() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                )

                Spacer(Modifier.height(16.dp))

                // 预览 + Hex 输入
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // 预览色块（当前色 vs 原始色）
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(currentColor())
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            stringResource(R.string.color_new),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(initialColor)
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            stringResource(R.string.color_old),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    OutlinedTextField(
                        value = hexInput,
                        onValueChange = { input ->
                            hexInput = input
                            val parsed = hexToColor(input)
                            if (parsed != null) {
                                hexError = false
                                val argb = parsed.toArgb()
                                val hsv = FloatArray(3)
                                AndroidColor.colorToHSV(argb or 0xFF000000.toInt(), hsv)
                                hue = hsv[0]
                                saturation = hsv[1]
                                value = hsv[2]
                                alpha = (argb ushr 24) / 255f
                            } else {
                                hexError = true
                            }
                        },
                        label = { Text("Hex") },
                        isError = hexError,
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedBorderColor = MaterialTheme.colorScheme.tertiary,
                            focusedLabelColor = MaterialTheme.colorScheme.tertiary,
                            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            cursorColor = MaterialTheme.colorScheme.tertiary
                        )
                    )
                }

                Spacer(Modifier.height(16.dp))

                // 底部按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            stringResource(R.string.cancel),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    TextButton(onClick = { onConfirm(currentColor()) }) {
                        Text(
                            stringResource(R.string.confirm),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SaturationValuePanel(
    hue: Float,
    saturation: Float,
    value: Float,
    onSaturationValueChange: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val s = (offset.x / size.width).coerceIn(0f, 1f)
                    val v = 1f - (offset.y / size.height).coerceIn(0f, 1f)
                    onSaturationValueChange(s, v)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val s = (change.position.x / size.width).coerceIn(0f, 1f)
                    val v = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                    onSaturationValueChange(s, v)
                }
            }
    ) {
        val width = size.width
        val height = size.height

        // 绘制 SV 面板：水平方向为饱和度，垂直方向为明度
        for (x in 0..width.toInt()) {
            val s = x / width
            val topColor = AndroidColor.HSVToColor(floatArrayOf(hue, s, 1f))
            val bottomColor = AndroidColor.HSVToColor(floatArrayOf(hue, s, 0f))
            drawLine(
                brush = Brush.verticalGradient(
                    listOf(
                        Color(topColor),
                        Color(bottomColor)
                    )
                ),
                start = Offset(x.toFloat(), 0f),
                end = Offset(x.toFloat(), height),
                strokeWidth = 2f
            )
        }

        // 选色点
        val cx = saturation * width
        val cy = (1f - value) * height
        drawCircle(
            color = Color.White,
            radius = 10f,
            center = Offset(cx, cy),
            style = Stroke(width = 3f)
        )
        drawCircle(
            color = Color.Black,
            radius = 10f,
            center = Offset(cx, cy),
            style = Stroke(width = 1.5f)
        )
    }
}

@Composable
private fun HueBar(
    hue: Float,
    onHueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onHueChange((offset.x / size.width * 360f).coerceIn(0f, 360f))
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onHueChange((change.position.x / size.width * 360f).coerceIn(0f, 360f))
                }
            }
    ) {
        val width = size.width
        val height = size.height

        // 色相渐变
        val hueColors = (0..360 step 1).map { h ->
            Color(AndroidColor.HSVToColor(floatArrayOf(h.toFloat(), 1f, 1f)))
        }
        drawRect(
            brush = Brush.horizontalGradient(hueColors),
            size = size
        )

        // 指示器
        val x = (hue / 360f) * width
        drawCircle(
            color = Color.White,
            radius = height / 2f,
            center = Offset(x, height / 2f),
            style = Stroke(width = 3f)
        )
    }
}

@Composable
private fun AlphaBar(
    hue: Float,
    saturation: Float,
    value: Float,
    alpha: Float,
    onAlphaChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val baseColor = Color(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, value)))

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onAlphaChange((offset.x / size.width).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onAlphaChange((change.position.x / size.width).coerceIn(0f, 1f))
                }
            }
    ) {
        val width = size.width
        val height = size.height

        // 棋盘格背景
        val gridSize = 8f
        for (row in 0..(height / gridSize).toInt()) {
            for (col in 0..(width / gridSize).toInt()) {
                drawRect(
                    color = if ((row + col) % 2 == 0) Color.White else Color.LightGray,
                    topLeft = Offset(col * gridSize, row * gridSize),
                    size = androidx.compose.ui.geometry.Size(gridSize, gridSize)
                )
            }
        }

        // 透明度渐变
        drawRect(
            brush = Brush.horizontalGradient(
                listOf(baseColor.copy(alpha = 0f), baseColor.copy(alpha = 1f))
            ),
            size = size
        )

        // 指示器
        val x = alpha * width
        drawCircle(
            color = Color.White,
            radius = height / 2f,
            center = Offset(x, height / 2f),
            style = Stroke(width = 3f)
        )
    }
}

private fun colorToHex(color: Color): String {
    val argb = color.toArgb()
    return "#%08X".format(argb)
}

private fun hexToColor(hex: String): Color? {
    val cleaned = hex.removePrefix("#").trim()
    return try {
        when (cleaned.length) {
            6 -> {
                val rgb = cleaned.toLong(16)
                Color((0xFF000000 or rgb).toInt())
            }

            8 -> {
                val argb = cleaned.toLong(16)
                Color(argb.toInt())
            }

            else -> null
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * 将 Color 转为 ARGB Long (0xAARRGGBB)
 */
fun Color.toArgbLong(): Long = this.toArgb().toLong() and 0xFFFFFFFFL
