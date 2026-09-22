package com.ikunshare.sound.tool

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

object AmbientColorExtractor {

    /**
     * 从 Bitmap 提取氛围色（低饱和度、低透明度）
     * @param bitmap 封面图
     * @param targetSaturation 目标饱和度 (0.0~1.0)，默认 0.4
     * @param targetAlpha 目标透明度 (0.0~1.0)，默认 0.25
     * @return 适合作为主页背景的淡氛围色，失败返回 Color.Transparent
     */
    suspend fun extractAmbientColor(
        bitmap: Bitmap?,
        targetSaturation: Float = 0.4f,
        targetAlpha: Float = 0.25f
    ): Color = withContext(Dispatchers.Default) {
        if (bitmap == null || bitmap.width == 0 || bitmap.height == 0) {
            return@withContext Color.Transparent
        }

        try {
            // 缩小 Bitmap 加速分析（最大边不超过 128px）
            val scaledBitmap = scaleBitmap(bitmap, 128)

            val palette = Palette.from(scaledBitmap).generate()

            // 优先级：Vibrant > LightVibrant > Dominant > Muted
            val dominantSwatch = palette.vibrantSwatch
                ?: palette.lightVibrantSwatch
                ?: palette.dominantSwatch
                ?: palette.mutedSwatch

            if (dominantSwatch == null) {
                return@withContext Color.Transparent
            }

            val rgb = dominantSwatch.rgb
            val color = Color(rgb)

            // 转换到 HSL，降低饱和度
            val hsl = rgbToHsl(color)
            val ambientHsl = hsl.copy(
                saturation = (hsl.saturation * targetSaturation).coerceIn(0f, 1f)
            )

            // 转回 RGB 并设置透明度
            hslToRgb(ambientHsl).copy(alpha = targetAlpha)

        } catch (e: Exception) {
            Color.Transparent
        }
    }

    /**
     * 缩放 Bitmap（保持比例）
     */
    private fun scaleBitmap(source: Bitmap, maxSize: Int): Bitmap {
        val width = source.width
        val height = source.height
        if (width <= maxSize && height <= maxSize) return source

        val scale = maxSize.toFloat() / max(width, height)
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        return Bitmap.createScaledBitmap(source, newWidth, newHeight, true)
    }

    /**
     * RGB → HSL 转换
     */
    private fun rgbToHsl(color: Color): HSL {
        val r = color.red
        val g = color.green
        val b = color.blue

        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val delta = maxC - minC

        val lightness = (maxC + minC) / 2f

        if (delta == 0f) {
            return HSL(0f, 0f, lightness)
        }

        val saturation = if (lightness < 0.5f) {
            delta / (maxC + minC)
        } else {
            delta / (2f - maxC - minC)
        }

        val hue = when (maxC) {
            r -> ((g - b) / delta + if (g < b) 6f else 0f) / 6f
            g -> ((b - r) / delta + 2f) / 6f
            else -> ((r - g) / delta + 4f) / 6f
        }

        return HSL(hue, saturation, lightness)
    }

    /**
     * HSL → RGB 转换
     */
    private fun hslToRgb(hsl: HSL): Color {
        val h = hsl.hue
        val s = hsl.saturation
        val l = hsl.lightness

        if (s == 0f) {
            return Color(l, l, l)
        }

        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q

        val r = hueToRgb(p, q, h + 1f / 3f)
        val g = hueToRgb(p, q, h)
        val b = hueToRgb(p, q, h - 1f / 3f)

        return Color(r, g, b)
    }

    private fun hueToRgb(p: Float, q: Float, t: Float): Float {
        var tNorm = t
        if (tNorm < 0f) tNorm += 1f
        if (tNorm > 1f) tNorm -= 1f

        return when {
            tNorm < 1f / 6f -> p + (q - p) * 6f * tNorm
            tNorm < 1f / 2f -> q
            tNorm < 2f / 3f -> p + (q - p) * (2f / 3f - tNorm) * 6f
            else -> p
        }
    }

    private data class HSL(val hue: Float, val saturation: Float, val lightness: Float)
}
