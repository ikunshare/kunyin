package com.ikunshare.music.ui.screens.player.mesh

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.util.Log
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 进程级状态：跨播放器开关保持流动相位连续，并记住上次封面以避免重复淡入。
 */
internal object MeshBgState {
    @Volatile
    var frameTimeMs: Float = 0f

    @Volatile
    var lastCoverKey: String? = null
}

private data class AlbumRequest(val bitmap: Bitmap, val key: String)

/** 与桌面端 AmllBackground.vue 对齐的渲染参数。 */
private const val RENDER_SCALE = 0.3f          // r.setRenderScale(0.3)
private const val OVERSCAN = 1.3f              // CSS transform: scale(1.3)
private const val TARGET_FPS = 30              // r.setFPS(30)
private const val LOW_FREQ_VOLUME = 1f / 10f   // r.setLowFreqVolume(1) → volume / 10
/** 同时保留的网格状态上限（当前 + 一个正在淡出的旧状态），切歌过快时不会堆积。 */
private const val MAX_MESH_STATES = 2
/** 网格以 ~5% 透明度叠在底色上，底色取桌面端 .player-page 的 #101013。 */
private const val BASE_R = 0x10 / 255f
private const val BASE_G = 0x10 / 255f
private const val BASE_B = 0x13 / 255f

private class MeshState(
    val mesh: BHPMesh,
    val texture: GLTexture,
    var alpha: Float,
)

/**
 * Mesh Gradient 渲染器，移植自 AMLL `MeshGradientRenderer`。
 * 每个网格状态先渲染到 FBO，再以 alpha 淡入淡出叠加到屏幕，实现换歌交叉过渡。
 * 由渲染线程在持有 EGL 上下文时调用 [onSurfaceCreated]/[onSurfaceChanged]/[onDrawFrame]。
 */
private class AlbumMeshRenderer {
    @Volatile
    var flowSpeed = 1f

    @Volatile
    private var pendingAlbum: AlbumRequest? = null

    @Volatile
    private var noCover = true

    private var mainProgram: GLProgram? = null
    private var quadProgram: GLProgram? = null
    private var quadBuffer = 0
    private var fbo = 0
    private var fboTexture = 0
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var renderWidth = 0
    private var renderHeight = 0

    private val meshStates = ArrayList<MeshState>()
    private var frameTime = MeshBgState.frameTimeMs
    private var lastFrameNanos = 0L

    fun setAlbum(bitmap: Bitmap, key: String) {
        pendingAlbum = AlbumRequest(bitmap, key)
        noCover = false
    }

    fun clearAlbum() {
        pendingAlbum = null
        noCover = true
    }

    fun onSurfaceCreated() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)

        mainProgram = GLProgram(MESH_VERT_SHADER, MESH_FRAG_SHADER, "mesh-main")
        quadProgram = GLProgram(QUAD_VERT_SHADER, QUAD_FRAG_SHADER, "mesh-quad")

        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        quadBuffer = ids[0]
        val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f, 1f)
        val buf: FloatBuffer = ByteBuffer.allocateDirect(quad.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(quad).position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadBuffer)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, quad.size * 4, buf, GLES20.GL_STATIC_DRAW)

        // 上下文重建后旧的 mesh/texture 随旧 context 失效，需清空重建。
        meshStates.clear()
        fbo = 0
        fboTexture = 0
        lastFrameNanos = 0L
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        GLES20.glViewport(0, 0, width, height)
        // 低渲染比例 + 过扫描（见 OVERSCAN）：BHP 网格 patch 交界在强色差下会露出
        // 多边形接缝，低分辨率渲染把接缝糊化、过扫描把边缘裁出屏幕，同时更省性能。
        renderWidth = (width * RENDER_SCALE).toInt().coerceAtLeast(1)
        renderHeight = (height * RENDER_SCALE).toInt().coerceAtLeast(1)
        updateFbo(renderWidth, renderHeight)
    }

    private fun updateFbo(width: Int, height: Int) {
        if (fbo != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        if (fboTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(fboTexture), 0)

        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        fboTexture = texIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexture)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val fboIds = IntArray(1)
        GLES20.glGenFramebuffers(1, fboIds, 0)
        fbo = fboIds[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTexture, 0
        )
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun consumePendingAlbum() {
        val req = pendingAlbum ?: return
        pendingAlbum = null
        val main = mainProgram ?: return
        try {
            buildMeshState(req, main)
        } catch (e: Exception) {
            Log.w("MeshBgGL", "build mesh failed", e)
        }
    }

    private fun buildMeshState(req: AlbumRequest, main: GLProgram) {
        val imageBitmap = processAlbumBitmap(req.bitmap)
        val texture = GLTexture(imageBitmap)
        imageBitmap.recycle()

        val rng = Random(req.key.hashCode().toLong())
        val chosen = if (rng.nextFloat() > 0.8f) {
            generateControlPoints(6, 6, rng)
        } else {
            CONTROL_POINT_PRESETS[rng.nextInt(CONTROL_POINT_PRESETS.size)]
        }

        val mesh = BHPMesh(
            main.attrs["a_pos"] ?: 0,
            main.attrs["a_color"] ?: -1,
            main.attrs["a_uv"] ?: -1,
        )
        mesh.resetSubdivision(50)
        mesh.resizeControlPoints(chosen.width, chosen.height)
        val uPower = 2f / (chosen.width - 1)
        val vPower = 2f / (chosen.height - 1)
        for (c in chosen.conf) {
            val point = mesh.getControlPoint(c.cx, c.cy)
            point.locX = c.x
            point.locY = c.y
            point.uRot = (c.ur * Math.PI.toFloat()) / 180f
            point.vRot = (c.vr * Math.PI.toFloat()) / 180f
            point.uScale = uPower * c.up
            point.vScale = vPower * c.vp
        }
        mesh.updateMesh()

        // 同一封面重开：直接以完全显示状态出现，避免又淡入一遍。
        val sameAsLast = req.key == MeshBgState.lastCoverKey
        MeshBgState.lastCoverKey = req.key
        while (meshStates.size >= MAX_MESH_STATES) {
            val stale = meshStates.removeAt(0)
            stale.mesh.dispose(); stale.texture.dispose()
        }
        meshStates.add(MeshState(mesh, texture, if (sameAsLast) 1.1f else 0f))
    }

    fun onDrawFrame() {
        consumePendingAlbum()

        val nowNanos = System.nanoTime()
        if (lastFrameNanos == 0L) lastFrameNanos = nowNanos
        val delta = ((nowNanos - lastFrameNanos) / 1_000_000.0).toFloat().coerceIn(0f, 100f)
        lastFrameNanos = nowNanos
        frameTime += delta * flowSpeed
        MeshBgState.frameTimeMs = frameTime

        updateAlphas(delta)

        // 屏幕清为不透明底色，保证背景整体不透明。
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glClearColor(BASE_R, BASE_G, BASE_B, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val main = mainProgram ?: return
        val quad = quadProgram ?: return
        if (fbo == 0 || surfaceWidth == 0 || surfaceHeight == 0) return

        val uTime = frameTime / 10000f
        val aspect = surfaceWidth.toFloat() / surfaceHeight.toFloat()
        val angle = (uTime + LOW_FREQ_VOLUME) * 2f
        val sinAngle = sin(angle)
        val cosAngle = cos(angle)

        for (state in meshStates) {
            // 1) 渲染网格到 FBO
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glViewport(0, 0, renderWidth, renderHeight)
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            main.use()
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            main.setUniform1f("u_aspect", aspect)
            main.setUniform1f("u_overscan", OVERSCAN)
            main.setUniform1i("u_texture", 0)
            main.setUniform1f("u_volume", LOW_FREQ_VOLUME)
            main.setUniform1f("u_alpha", 1f)
            main.setUniform1f("u_sinAngle", sinAngle)
            main.setUniform1f("u_cosAngle", cosAngle)
            state.texture.bind()
            state.mesh.bind()
            state.mesh.draw()

            // 2) 把 FBO 叠加到屏幕（带淡入淡出）
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFuncSeparate(
                GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA,
                GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA
            )
            quad.use()
            quad.setUniform1i("u_texture", 0)
            quad.setUniform1f("u_alpha", easeInOutSine(clamp01(state.alpha)))
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexture)

            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadBuffer)
            val aPos = quad.attrs["a_pos"] ?: 0
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, 0)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
            GLES20.glDisableVertexAttribArray(aPos)
        }
        GLES20.glFlush()
    }

    private fun updateAlphas(delta: Float) {
        val deltaFactor = delta / 500f
        if (meshStates.isEmpty()) return
        if (noCover) {
            var i = meshStates.size - 1
            while (i >= 0) {
                val s = meshStates[i]
                if (s.alpha <= -0.1f) {
                    s.mesh.dispose(); s.texture.dispose()
                    meshStates.removeAt(i)
                } else {
                    s.alpha = maxOf(-0.1f, s.alpha - deltaFactor)
                }
                i--
            }
        } else {
            val latest = meshStates.last()
            if (latest.alpha >= 1.1f) {
                while (meshStates.size > 1) {
                    val s = meshStates.removeAt(0)
                    s.mesh.dispose(); s.texture.dispose()
                }
            } else {
                latest.alpha = minOf(1.1f, latest.alpha + deltaFactor)
            }
        }
    }

    companion object {
        private fun clamp01(x: Float) = if (x < 0f) 0f else if (x > 1f) 1f else x
        private fun easeInOutSine(x: Float) = (-(cos(Math.PI * x) - 1) / 2).toFloat()
    }
}

// ─────────────────────────── 封面图预处理（img.ts 端口） ───────────────────────────

private const val ALBUM_SIZE = 32

private fun processAlbumBitmap(src: Bitmap): Bitmap {
    val scaled = Bitmap.createScaledBitmap(src, ALBUM_SIZE, ALBUM_SIZE, true)
    val w = ALBUM_SIZE
    val h = ALBUM_SIZE
    val argb = IntArray(w * h)
    scaled.getPixels(argb, 0, w, 0, 0, w, h)
    if (scaled !== src) scaled.recycle()

    val px = IntArray(w * h * 4)
    for (i in 0 until w * h) {
        val c = argb[i]
        var r = ((c shr 16) and 0xFF).toFloat()
        var g = ((c shr 8) and 0xFF).toFloat()
        var b = (c and 0xFF).toFloat()

        // contrast 0.4
        r = (r - 128f) * 0.4f + 128f
        g = (g - 128f) * 0.4f + 128f
        b = (b - 128f) * 0.4f + 128f
        // saturate 3.0
        val gray = r * 0.3f + g * 0.59f + b * 0.11f
        r = gray * -2.0f + r * 3.0f
        g = gray * -2.0f + g * 3.0f
        b = gray * -2.0f + b * 3.0f
        // contrast 1.7
        r = (r - 128f) * 1.7f + 128f
        g = (g - 128f) * 1.7f + 128f
        b = (b - 128f) * 1.7f + 128f
        // brightness 0.75
        val idx = i * 4
        px[idx] = clamp255(r * 0.75f)
        px[idx + 1] = clamp255(g * 0.75f)
        px[idx + 2] = clamp255(b * 0.75f)
        px[idx + 3] = (c ushr 24) and 0xFF
    }

    // 环形模糊：使纹理四方连续（配合 GLTexture 的 REPEAT 采样）。
    blurChannelsToroidal(px, w, h, radius = 2, quality = 4)

    val out = IntArray(w * h)
    for (i in 0 until w * h) {
        val idx = i * 4
        out[i] = (px[idx + 3] shl 24) or (px[idx] shl 16) or (px[idx + 1] shl 8) or px[idx + 2]
    }
    return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
}

private fun clamp255(v: Float): Int = when {
    v <= 0f -> 0
    v >= 255f -> 255
    else -> (v + 0.5f).toInt()
}

/**
 * 环形（toroidal）盒式模糊，端口自 img.ts 的 blurImageToroidal（RGBA 展开数组）：
 * 索引越界按宽/高回绕，使左右边、上下边互为邻居。产出的图四方连续，配合 REPEAT 采样时
 * 纹理边界与内部一样平滑——这是旋转采样越出 [0,1] 时不出现「折痕弧线」的关键。
 */
private fun blurChannelsToroidal(pixels: IntArray, width: Int, height: Int, radius: Int, quality: Int) {
    val div = radius * 2 + 1
    val div2 = 1.0 / (div * div)

    val r = IntArray(width * height)
    val g = IntArray(width * height)
    val b = IntArray(width * height)
    val a = IntArray(width * height)

    var q = quality
    while (q-- > 0) {
        // 水平：对每行做滑动窗口，x 越界回绕
        for (y in 0 until height) {
            val yw = y * width * 4
            var rsum = 0; var gsum = 0; var bsum = 0; var asum = 0
            for (i in -radius..radius) {
                val p = yw + (((i + width) % width) shl 2)
                rsum += pixels[p]; gsum += pixels[p + 1]; bsum += pixels[p + 2]; asum += pixels[p + 3]
            }
            var yi = y * width
            for (x in 0 until width) {
                r[yi] = rsum; g[yi] = gsum; b[yi] = bsum; a[yi] = asum
                val p1 = yw + (((x + radius + 1) % width) shl 2)
                val p2 = yw + (((x - radius + width) % width) shl 2)
                rsum += pixels[p1] - pixels[p2]
                gsum += pixels[p1 + 1] - pixels[p2 + 1]
                bsum += pixels[p1 + 2] - pixels[p2 + 2]
                asum += pixels[p1 + 3] - pixels[p2 + 3]
                yi++
            }
        }
        // 垂直：对行和结果做滑动窗口，y 越界回绕
        for (x in 0 until width) {
            var rsum = 0; var gsum = 0; var bsum = 0; var asum = 0
            for (i in -radius..radius) {
                val p = ((i + height) % height) * width + x
                rsum += r[p]; gsum += g[p]; bsum += b[p]; asum += a[p]
            }
            var yi = x shl 2
            for (y in 0 until height) {
                pixels[yi] = (rsum * div2 + 0.5).toInt()
                pixels[yi + 1] = (gsum * div2 + 0.5).toInt()
                pixels[yi + 2] = (bsum * div2 + 0.5).toInt()
                pixels[yi + 3] = (asum * div2 + 0.5).toInt()
                val p1 = ((y + radius + 1) % height) * width + x
                val p2 = ((y - radius + height) % height) * width + x
                rsum += r[p1] - r[p2]
                gsum += g[p1] - g[p2]
                bsum += b[p1] - b[p2]
                asum += a[p1] - a[p2]
                yi += width shl 2
            }
        }
    }
}

// ─────────────────────────── TextureView + EGL 渲染线程 ───────────────────────────

private class MeshRenderThread(
    private val surface: SurfaceTexture,
    private val renderer: AlbumMeshRenderer,
) : Thread("MeshBgGL") {
    @Volatile private var running = true
    @Volatile private var paused = false
    @Volatile private var width = 0
    @Volatile private var height = 0
    @Volatile private var sizeDirty = true

    fun setSize(w: Int, h: Int) {
        width = w; height = h; sizeDirty = true
    }

    fun requestStop() { running = false }
    fun setPaused(p: Boolean) { paused = p }

    override fun run() {
        var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var context: EGLContext = EGL14.EGL_NO_CONTEXT
        var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val ver = IntArray(2)
            EGL14.eglInitialize(display, ver, 0, ver, 1)
            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_DEPTH_SIZE, 0, EGL14.EGL_STENCIL_SIZE, 0,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfig = IntArray(1)
            EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfig, 0)
            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            eglSurface = EGL14.eglCreateWindowSurface(
                display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0
            )
            if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
                throw RuntimeException("eglMakeCurrent failed")
            }

            renderer.onSurfaceCreated()

            while (running) {
                if (paused) { sleep(16); continue }
                if (sizeDirty && width > 0 && height > 0) {
                    renderer.onSurfaceChanged(width, height)
                    sizeDirty = false
                }
                if (width > 0 && height > 0) {
                    val frameStart = System.nanoTime()
                    renderer.onDrawFrame()
                    EGL14.eglSwapBuffers(display, eglSurface)
                    // 与桌面端一致限制在 30fps：背景本就是缓慢流动，省电优先。
                    val frameIntervalMs = 1000L / TARGET_FPS
                    val elapsedMs = (System.nanoTime() - frameStart) / 1_000_000
                    if (elapsedMs < frameIntervalMs) sleep(frameIntervalMs - elapsedMs)
                } else {
                    sleep(16)
                }
            }
        } catch (e: Exception) {
            Log.w("MeshBgGL", "render thread error", e)
        } finally {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                )
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
        }
    }
}

class AlbumMeshBackgroundView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    private val meshRenderer = AlbumMeshRenderer()
    private var renderThread: MeshRenderThread? = null
    private var lastBitmap: Bitmap? = null
    private var lastKey: String = ""

    init {
        isOpaque = true
        surfaceTextureListener = this
    }

    fun setAlbum(bitmap: Bitmap, key: String) {
        if (key == lastKey && bitmap === lastBitmap) return
        lastBitmap = bitmap
        lastKey = key
        meshRenderer.setAlbum(bitmap, key)
    }

    fun setFlowSpeed(speed: Float) {
        meshRenderer.flowSpeed = speed
    }

    fun clearAlbum() {
        if (lastBitmap == null) return
        lastBitmap = null
        lastKey = ""
        meshRenderer.clearAlbum()
    }

    fun onPause() {
        renderThread?.setPaused(true)
    }

    fun onResume() {
        renderThread?.setPaused(false)
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        val thread = MeshRenderThread(st, meshRenderer)
        thread.setSize(width, height)
        thread.start()
        renderThread = thread
        // 上下文重建后重新提交当前封面，以便渲染器重建网格。
        lastBitmap?.let { if (!it.isRecycled) meshRenderer.setAlbum(it, lastKey) }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
        renderThread?.setSize(width, height)
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        renderThread?.requestStop()
        try {
            renderThread?.join(500)
        } catch (_: InterruptedException) {
        }
        renderThread = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
}

/**
 * Compose 封装：把专辑封面喂给 Mesh Gradient 背景。coverKey 变化时才重建网格。
 */
@Composable
internal fun AlbumMeshBackground(
    coverBitmap: Bitmap?,
    coverKey: String,
    modifier: Modifier = Modifier,
    flowSpeed: Float = 1f,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var view by remember { mutableStateOf<AlbumMeshBackgroundView?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { ctx -> AlbumMeshBackgroundView(ctx).also { view = it } },
        update = { v ->
            v.setFlowSpeed(flowSpeed)
            if (coverBitmap != null && !coverBitmap.isRecycled && coverKey.isNotEmpty()) {
                v.setAlbum(coverBitmap, coverKey)
            } else v.clearAlbum()
        }
    )

    DisposableEffect(lifecycleOwner, view) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> view?.onPause()
                Lifecycle.Event.ON_RESUME -> view?.onResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            view?.onPause()
        }
    }
}
