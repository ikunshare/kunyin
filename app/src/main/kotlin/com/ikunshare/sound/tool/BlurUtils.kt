package com.ikunshare.sound.tool

import android.graphics.Bitmap

/**
 * Bitmap 模糊工具。
 *
 * 先将 Bitmap 缩小到 ~200px 宽度，然后使用 StackBlur 算法模糊，
 * 兼容所有 API 级别且不依赖 RenderScript。
 */
object BlurUtils {

    /**
     * 对 bitmap 进行模糊处理。
     *
     * @param bitmap 原始图片
     * @param radius 模糊半径 (1-25)
     * @return 模糊后的 Bitmap
     */
    fun blurBitmap(bitmap: Bitmap, radius: Int = 25): Bitmap {
        // 缩小到 ~200px 宽度以减少计算量
        val scaleFactor = (200f / bitmap.width).coerceAtMost(1f)
        val scaledWidth = (bitmap.width * scaleFactor).toInt().coerceAtLeast(1)
        val scaledHeight = (bitmap.height * scaleFactor).toInt().coerceAtLeast(1)

        val input = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
        val output = input.copy(Bitmap.Config.ARGB_8888, true)

        stackBlur(output, radius.coerceIn(1, 25))
        return output
    }

    /**
     * StackBlur 算法实现。
     * 参考 Mario Klingemann 的 StackBlur 算法。
     */
    private fun stackBlur(bitmap: Bitmap, radius: Int) {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val div = 2 * radius + 1
        val divSum = (radius + 1) * (radius + 1)

        val r = IntArray(w * h)
        val g = IntArray(w * h)
        val b = IntArray(w * h)

        IntArray(maxOf(w, h))

        var rSum: Int
        var gSum: Int
        var bSum: Int
        var rOutSum: Int
        var gOutSum: Int
        var bOutSum: Int
        var rInSum: Int
        var gInSum: Int
        var bInSum: Int

        val stack = Array(div) { IntArray(3) }
        var stackPointer: Int
        var stackStart: Int
        var sir: IntArray
        var rbs: Int
        val mul = IntArray(256)
        val shr = IntArray(256)

        for (i in 0 until 256) {
            mul[i] = i * (radius + 1)
            shr[i] = 0
        }

        // Horizontal pass
        for (y in 0 until h) {
            rSum = 0; gSum = 0; bSum = 0
            rInSum = 0; gInSum = 0; bInSum = 0
            rOutSum = 0; gOutSum = 0; bOutSum = 0

            for (i in -radius..radius) {
                val p = pixels[y * w + i.coerceIn(0, w - 1)]
                sir = stack[i + radius]
                sir[0] = (p shr 16) and 0xFF
                sir[1] = (p shr 8) and 0xFF
                sir[2] = p and 0xFF

                rbs = radius + 1 - kotlin.math.abs(i)
                rSum += sir[0] * rbs
                gSum += sir[1] * rbs
                bSum += sir[2] * rbs

                if (i > 0) {
                    rInSum += sir[0]; gInSum += sir[1]; bInSum += sir[2]
                } else {
                    rOutSum += sir[0]; gOutSum += sir[1]; bOutSum += sir[2]
                }
            }
            stackPointer = radius

            for (x in 0 until w) {
                r[y * w + x] = rSum / divSum
                g[y * w + x] = gSum / divSum
                b[y * w + x] = bSum / divSum

                rSum -= rOutSum; gSum -= gOutSum; bSum -= bOutSum

                stackStart = stackPointer - radius + div
                sir = stack[stackStart % div]

                rOutSum -= sir[0]; gOutSum -= sir[1]; bOutSum -= sir[2]

                val nextX = (x + radius + 1).coerceAtMost(w - 1)
                val p = pixels[y * w + nextX]
                sir[0] = (p shr 16) and 0xFF
                sir[1] = (p shr 8) and 0xFF
                sir[2] = p and 0xFF

                rInSum += sir[0]; gInSum += sir[1]; bInSum += sir[2]
                rSum += rInSum; gSum += gInSum; bSum += bInSum

                stackPointer = (stackPointer + 1) % div
                sir = stack[stackPointer]

                rOutSum += sir[0]; gOutSum += sir[1]; bOutSum += sir[2]
                rInSum -= sir[0]; gInSum -= sir[1]; bInSum -= sir[2]
            }
        }

        // Vertical pass
        for (x in 0 until w) {
            rSum = 0; gSum = 0; bSum = 0
            rInSum = 0; gInSum = 0; bInSum = 0
            rOutSum = 0; gOutSum = 0; bOutSum = 0

            for (i in -radius..radius) {
                val yi = i.coerceIn(0, h - 1)
                sir = stack[i + radius]
                sir[0] = r[yi * w + x]
                sir[1] = g[yi * w + x]
                sir[2] = b[yi * w + x]

                rbs = radius + 1 - kotlin.math.abs(i)
                rSum += sir[0] * rbs
                gSum += sir[1] * rbs
                bSum += sir[2] * rbs

                if (i > 0) {
                    rInSum += sir[0]; gInSum += sir[1]; bInSum += sir[2]
                } else {
                    rOutSum += sir[0]; gOutSum += sir[1]; bOutSum += sir[2]
                }
            }
            stackPointer = radius

            for (y in 0 until h) {
                pixels[y * w + x] = (0xFF000000.toInt()) or
                        ((rSum / divSum).coerceIn(0, 255) shl 16) or
                        ((gSum / divSum).coerceIn(0, 255) shl 8) or
                        (bSum / divSum).coerceIn(0, 255)

                rSum -= rOutSum; gSum -= gOutSum; bSum -= bOutSum

                stackStart = stackPointer - radius + div
                sir = stack[stackStart % div]

                rOutSum -= sir[0]; gOutSum -= sir[1]; bOutSum -= sir[2]

                val nextY = (y + radius + 1).coerceAtMost(h - 1)
                sir[0] = r[nextY * w + x]
                sir[1] = g[nextY * w + x]
                sir[2] = b[nextY * w + x]

                rInSum += sir[0]; gInSum += sir[1]; bInSum += sir[2]
                rSum += rInSum; gSum += gInSum; bSum += bInSum

                stackPointer = (stackPointer + 1) % div
                sir = stack[stackPointer]

                rOutSum += sir[0]; gOutSum += sir[1]; bOutSum += sir[2]
                rInSum -= sir[0]; gInSum -= sir[1]; bInSum -= sir[2]
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }
}
