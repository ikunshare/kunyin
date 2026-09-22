package com.ikunshare.sound.ui.screens.player.mesh

import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Mesh Gradient 控制点数据与生成器。
 *
 * 移植自 AMLL（Apple Music-Like Lyrics，作者 SteveXMH）的 `bg-render/mesh-renderer`：
 * - cp-presets.ts → [CONTROL_POINT_PRESETS]
 * - cp-generate.ts → [generateControlPoints]
 */

/** 单个控制点配置：网格坐标 (cx,cy)、位置 (x,y)∈[-1,1]、u/v 切线旋转角(度)与缩放系数。 */
data class ControlPointConf(
    val cx: Int,
    val cy: Int,
    val x: Float,
    val y: Float,
    val ur: Float = 0f,
    val vr: Float = 0f,
    val up: Float = 1f,
    val vp: Float = 1f,
)

data class ControlPointPreset(
    val width: Int,
    val height: Int,
    val conf: List<ControlPointConf>,
)

private fun p(
    cx: Int, cy: Int, x: Double, y: Double,
    ur: Double = 0.0, vr: Double = 0.0, up: Double = 1.0, vp: Double = 1.0,
) = ControlPointConf(cx, cy, x.toFloat(), y.toFloat(), ur.toFloat(), vr.toFloat(), up.toFloat(), vp.toFloat())

private fun preset(width: Int, height: Int, conf: List<ControlPointConf>) =
    ControlPointPreset(width, height, conf)

val CONTROL_POINT_PRESETS: List<ControlPointPreset> = listOf(
    // 竖屏推荐
    preset(5, 5, listOf(
        p(0, 0, -1.0, -1.0), p(1, 0, -0.5, -1.0), p(2, 0, 0.0, -1.0), p(3, 0, 0.5, -1.0), p(4, 0, 1.0, -1.0),
        p(0, 1, -1.0, -0.5), p(1, 1, -0.5, -0.5),
        p(2, 1, -0.0052029684413368305, -0.6131420587090777),
        p(3, 1, 0.5884227308309977, -0.3990805107556692), p(4, 1, 1.0, -0.5),
        p(0, 2, -1.0, 0.0), p(1, 2, -0.4210024670505933, -0.11895058380429502),
        p(2, 2, -0.1019613423315412, -0.023812118047224606, 0.0, -47.0, 0.629, 0.849),
        p(3, 2, 0.40275125660925437, -0.06345314544600389), p(4, 2, 1.0, 0.0),
        p(0, 3, -1.0, 0.5), p(1, 3, 0.06801958477287173, 0.5205913248960121, -31.0, -45.0, 1.0, 1.0),
        p(2, 3, 0.21446469120128908, 0.29331610114301043, 6.0, -56.0, 0.566, 1.321),
        p(3, 3, 0.5, 0.5), p(4, 3, 1.0, 0.5),
        p(0, 4, -1.0, 1.0), p(1, 4, -0.31378372841550195, 1.0), p(2, 4, 0.26153633255328046, 1.0),
        p(3, 4, 0.5, 1.0), p(4, 4, 1.0, 1.0),
    )),
    // 横屏推荐
    preset(4, 4, listOf(
        p(0, 0, -1.0, -1.0), p(1, 0, -0.33333333333333337, -1.0), p(2, 0, 0.33333333333333326, -1.0), p(3, 0, 1.0, -1.0),
        p(0, 1, -1.0, -0.04495399932657351), p(1, 1, -0.24056117520129328, -0.22465999020104),
        p(2, 1, 0.334758885767489, -0.00531297192779423),
        p(3, 1, 0.9989920470678106, -0.3382976020775408, 8.0, 0.0, 0.566, 1.792),
        p(0, 2, -1.0, 0.33333333333333326), p(1, 2, -0.3425497314639411, -0.000027501607956947893),
        p(2, 2, 0.3321437945812673, 0.1981776353859399), p(3, 2, 1.0, 0.0766118180296832),
        p(0, 3, -1.0, 1.0), p(1, 3, -0.33333333333333337, 1.0), p(2, 3, 0.33333333333333326, 1.0), p(3, 3, 1.0, 1.0),
    )),
    preset(4, 4, listOf(
        p(0, 0, -1.0, -1.0, 0.0, 0.0, 1.0, 2.075), p(1, 0, -0.33333333333333337, -1.0),
        p(2, 0, 0.33333333333333326, -1.0), p(3, 0, 1.0, -1.0),
        p(0, 1, -1.0, -0.4545779491139603), p(1, 1, -0.33333333333333337, -0.33333333333333337),
        p(2, 1, 0.0889403142626457, -0.6025711180694033, -32.0, 45.0, 1.0, 1.0),
        p(3, 1, 1.0, -0.33333333333333337),
        p(0, 2, -1.0, -0.07402408608567845, 1.0, 0.0, 1.0, 0.094),
        p(1, 2, -0.2719422694359541, 0.09775369930903222, 25.0, -18.0, 1.321, 0.0),
        p(2, 2, 0.19877414408395877, 0.4307383294587789, 48.0, -40.0, 0.755, 0.975),
        p(3, 2, 1.0, 0.33333333333333326, -37.0, 0.0, 1.0, 1.0),
        p(0, 3, -1.0, 1.0), p(1, 3, -0.33333333333333337, 1.0),
        p(2, 3, 0.5125850864305672, 1.0, -20.0, -18.0, 0.0, 1.604), p(3, 3, 1.0, 1.0),
    )),
    preset(5, 5, listOf(
        p(0, 0, -1.0, -1.0), p(1, 0, -0.4501953125, -1.0, 0.0, 55.0, 1.0, 2.075), p(2, 0, 0.1953125, -1.0),
        p(3, 0, 0.4580078125, -1.0, 0.0, -25.0, 1.0, 1.0), p(4, 0, 1.0, -1.0),
        p(0, 1, -1.0, -0.2514475377525607, -16.0, 0.0, 2.327, 0.943),
        p(1, 1, -0.55859375, -0.6609325945787148, 47.0, 0.0, 2.358, 0.377),
        p(2, 1, 0.232421875, -0.5244375756366635, -66.0, -25.0, 1.855, 1.164),
        p(3, 1, 0.685546875, -0.3753706470552125), p(4, 1, 1.0, -0.6699125300354287),
        p(0, 2, -1.0, 0.035910396862284255), p(1, 2, -0.4921875, 0.005378616309457018, 90.0, 23.0, 1.0, 1.981),
        p(2, 2, 0.021484375, -0.1365043639066228, 0.0, 42.0, 1.0, 1.0),
        p(3, 2, 0.4765625, 0.05925822904974043, -30.0, 0.0, 1.95, 0.44), p(4, 2, 1.0, 0.251428847823418),
        p(0, 3, -1.0, 0.6968336464764276, -68.0, 0.0, 1.0, 0.786), p(1, 3, -0.6904296875, 0.5890744209958608, -68.0, 0.0, 1.0, 1.0),
        p(2, 3, 0.1845703125, 0.3879238667654693, 61.0, 0.0, 1.0, 1.0),
        p(3, 3, 0.60546875, 0.4633553246018661, -47.0, -59.0, 0.849, 1.73),
        p(4, 3, 1.0, 0.6214021886400309, -33.0, 0.0, 0.377, 1.604),
        p(0, 4, -1.0, 1.0), p(1, 4, -0.5, 1.0, 0.0, -73.0, 1.0, 1.0),
        p(2, 4, -0.3271484375, 1.0, 0.0, -24.0, 0.314, 2.704), p(3, 4, 0.5, 1.0), p(4, 4, 1.0, 1.0),
    )),
    preset(5, 5, listOf(
        p(0, 0, -1.0, -1.0), p(1, 0, -0.6393, -1.0, 0.0, 0.0, 1.0, 2.3884), p(2, 0, 0.0, -1.0), p(3, 0, 0.5, -1.0), p(4, 0, 1.0, -1.0),
        p(0, 1, -1.0, -0.2301), p(1, 1, -0.6934, -0.331, 0.0, -0.7188, 1.0, 1.063),
        p(2, 1, -0.0082, -0.6814, -0.2583, 0.0, 1.0964, 1.0), p(3, 1, 0.5836, -0.531, 0.7029, 0.0, 1.5466, 1.0), p(4, 1, 1.0, -0.6407),
        p(0, 2, -1.0, 0.2973, 0.0, 0.0, 1.8352, 1.0), p(1, 2, -0.4082, 0.0602),
        p(2, 2, -0.1803, -0.3646, -0.2998, 0.0, 1.1513, 1.0), p(3, 2, 0.477, -0.1027, 0.8903, -0.1882, 1.0807, 0.8551), p(4, 2, 1.0, -0.2973),
        p(0, 3, -1.0, 0.7628, 0.0, 0.0, 2.3868, 1.0), p(1, 3, -0.2525, 0.4814, -0.8406, -1.6199, 1.4093, 1.2215),
        p(2, 3, 0.3607, 0.2814, -1.0713, -0.0529, 1.0025, 0.7611), p(3, 3, 0.4885, 0.623, 0.0, 0.8184, 1.0, 1.2876), p(4, 3, 1.0, 0.5),
        p(0, 4, -1.0, 1.0), p(1, 4, -0.4033, 1.0), p(2, 4, 0.2672, 1.0), p(3, 4, 0.5967, 1.0), p(4, 4, 1.0, 1.0),
    )),
    preset(5, 5, listOf(
        p(0, 0, -1.0, -1.0), p(1, 0, -0.2197, -1.0), p(2, 0, 0.0197, -1.0), p(3, 0, 0.8033, -1.0), p(4, 0, 1.0, -1.0),
        p(0, 1, -1.0, -0.5451), p(1, 1, -0.4885, -0.4035, -1.0246, -0.2268, 1.1936, 0.8005),
        p(2, 1, -0.1213, -0.2867, 0.0, -0.6981, 1.0, 0.809), p(3, 1, 0.3246, -0.5628, 0.0, -1.2188, 1.0, 1.044), p(4, 1, 1.0, -0.3292),
        p(0, 2, -1.0, 0.1416), p(1, 2, -0.341, -0.0142, 0.0, -0.4004, 1.0, 1.1293),
        p(2, 2, -0.0393, -0.023, 0.2915, -0.373, 1.044, 0.9879), p(3, 2, 0.3148, -0.0673, -0.7853, -0.8962, 1.4709, 1.0247), p(4, 2, 1.0, 0.1912),
        p(0, 3, -1.0, 0.5), p(1, 3, -0.2689, 0.2743, 0.3404, -0.5248, 1.0184, 0.4391),
        p(2, 3, 0.0721, 0.269, 0.5302, 0.1244, 0.6723, 0.3225), p(3, 3, 0.4148, 0.3894, -0.6977, -0.6783, 0.8094, 0.9247), p(4, 3, 1.0, 0.446),
        p(0, 4, -1.0, 1.0), p(1, 4, -0.7311, 1.0), p(2, 4, 0.323, 1.0), p(3, 4, 0.6393, 1.0), p(4, 4, 1.0, 1.0),
    )),
)

// ─────────────────────────── 随机生成器（cp-generate.ts） ───────────────────────────

private fun clamp01(x: Float): Float = if (x < 0f) 0f else if (x > 1f) 1f else x

private fun smoothstepF(edge0: Float, edge1: Float, x: Float): Float {
    val t = clamp01((x - edge0) / (edge1 - edge0))
    return t * t * (3f - 2f * t)
}

private fun fract(x: Float): Float = x - floor(x)

private fun noise2(x: Float, y: Float): Float =
    fract(sin(x * 12.9898f + y * 78.233f) * 43758.5453f)

private fun smoothNoise(x: Float, y: Float): Float {
    val x0 = floor(x); val y0 = floor(y)
    val x1 = x0 + 1f; val y1 = y0 + 1f
    val xf = x - x0; val yf = y - y0
    val u = xf * xf * (3f - 2f * xf)
    val v = yf * yf * (3f - 2f * yf)
    val n00 = noise2(x0, y0); val n10 = noise2(x1, y0)
    val n01 = noise2(x0, y1); val n11 = noise2(x1, y1)
    val nx0 = n00 * (1f - u) + n10 * u
    val nx1 = n01 * (1f - u) + n11 * u
    return nx0 * (1f - v) + nx1 * v
}

private fun computeNoiseGradient(x: Float, y: Float, epsilon: Float = 0.001f): Pair<Float, Float> {
    val n1 = smoothNoise(x + epsilon, y)
    val n2 = smoothNoise(x - epsilon, y)
    val n3 = smoothNoise(x, y + epsilon)
    val n4 = smoothNoise(x, y - epsilon)
    val dx = (n1 - n2) / (2f * epsilon)
    val dy = (n3 - n4) / (2f * epsilon)
    val len = sqrt(dx * dx + dy * dy).let { if (it == 0f) 1f else it }
    return dx / len to dy / len
}

private fun smoothifyControlPoints(
    conf: MutableList<ControlPointConf>,
    w: Int, h: Int,
    iterations: Int,
    factor: Float,
    factorIterationModifier: Float,
) {
    var grid = Array(h) { j -> Array(w) { i -> conf[j * w + i] } }
    var f = factor
    val kernel = arrayOf(intArrayOf(1, 2, 1), intArrayOf(2, 4, 2), intArrayOf(1, 2, 1))
    val kernelSum = 16f

    repeat(iterations) {
        val newGrid = Array(h) { arrayOfNulls<ControlPointConf>(w) }
        for (j in 0 until h) {
            for (i in 0 until w) {
                if (i == 0 || i == w - 1 || j == 0 || j == h - 1) {
                    newGrid[j][i] = grid[j][i]
                    continue
                }
                var sx = 0f; var sy = 0f; var sur = 0f; var svr = 0f; var sup = 0f; var svp = 0f
                for (dj in -1..1) for (di in -1..1) {
                    val weight = kernel[dj + 1][di + 1]
                    val nb = grid[j + dj][i + di]
                    sx += nb.x * weight; sy += nb.y * weight
                    sur += nb.ur * weight; svr += nb.vr * weight
                    sup += nb.up * weight; svp += nb.vp * weight
                }
                val cur = grid[j][i]
                newGrid[j][i] = ControlPointConf(
                    cur.cx, cur.cy,
                    cur.x * (1 - f) + (sx / kernelSum) * f,
                    cur.y * (1 - f) + (sy / kernelSum) * f,
                    cur.ur * (1 - f) + (sur / kernelSum) * f,
                    cur.vr * (1 - f) + (svr / kernelSum) * f,
                    cur.up * (1 - f) + (sup / kernelSum) * f,
                    cur.vp * (1 - f) + (svp / kernelSum) * f,
                )
            }
        }
        grid = Array(h) { j -> Array(w) { i -> newGrid[j][i]!! } }
        f = clamp01(f + factorIterationModifier)
    }

    for (j in 0 until h) for (i in 0 until w) conf[j * w + i] = grid[j][i]
}

/** 随机生成一套控制点，替代固定预设，带来更多变化。移植自 cp-generate.ts。 */
fun generateControlPoints(width: Int, height: Int, rng: Random = Random.Default): ControlPointPreset {
    fun range(min: Float, max: Float) = rng.nextFloat() * (max - min) + min

    val variationFraction = range(0.4f, 0.6f)
    val normalOffset = range(0.3f, 0.6f)
    val blendFactor = 0.8f
    val smoothIters = floor(range(3f, 5f)).toInt()
    val smoothFactor = range(0.2f, 0.3f)
    val smoothModifier = range(-0.1f, -0.05f)

    val w = width
    val h = height
    val conf = ArrayList<ControlPointConf>(w * h)
    val dx = if (w == 1) 0f else 2f / (w - 1)
    val dy = if (h == 1) 0f else 2f / (h - 1)

    for (j in 0 until h) {
        for (i in 0 until w) {
            val baseX = (if (w == 1) 0f else i.toFloat() / (w - 1)) * 2f - 1f
            val baseY = (if (h == 1) 0f else j.toFloat() / (h - 1)) * 2f - 1f
            val isBorder = i == 0 || i == w - 1 || j == 0 || j == h - 1
            val pertX = if (isBorder) 0f else range(-variationFraction * dx, variationFraction * dx)
            val pertY = if (isBorder) 0f else range(-variationFraction * dy, variationFraction * dy)
            var x = baseX + pertX
            var y = baseY + pertY
            val ur = if (isBorder) 0f else range(-60f, 60f)
            val vr = if (isBorder) 0f else range(-60f, 60f)
            val up = if (isBorder) 1f else range(0.8f, 1.2f)
            val vp = if (isBorder) 1f else range(0.8f, 1.2f)

            if (!isBorder) {
                val uNorm = (baseX + 1f) / 2f
                val vNorm = (baseY + 1f) / 2f
                val (nx, ny) = computeNoiseGradient(uNorm, vNorm, 0.001f)
                var offsetX = nx * normalOffset
                var offsetY = ny * normalOffset
                val distToBorder = minOf(uNorm, 1f - uNorm, vNorm, 1f - vNorm)
                val weight = smoothstepF(0f, 1.0f, distToBorder)
                offsetX *= weight; offsetY *= weight
                x = x * (1 - blendFactor) + (x + offsetX) * blendFactor
                y = y * (1 - blendFactor) + (y + offsetY) * blendFactor
            }
            conf.add(ControlPointConf(i, j, x, y, ur, vr, up, vp))
        }
    }

    smoothifyControlPoints(conf, w, h, smoothIters, smoothFactor, smoothModifier)
    return ControlPointPreset(w, h, conf)
}
