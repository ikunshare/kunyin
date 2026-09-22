package com.ikunshare.sound.ui.screens.player.mesh

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.cos
import kotlin.math.sin

/**
 * Mesh Gradient 的 OpenGL ES 2.0 底层设施：着色器、GL 程序、纹理、控制点、双三次 Hermite 网格。
 * 移植自 AMLL `bg-render/mesh-renderer/index.ts` 与 `mesh.*.glsl`。
 */

// ─────────────────────────── 着色器（GLSL ES 100，几乎原样自 AMLL） ───────────────────────────

internal const val MESH_VERT_SHADER = """
precision highp float;
attribute vec2 a_pos;
attribute vec3 a_color;
attribute vec2 a_uv;
varying vec3 v_color;
varying vec2 v_uv;
uniform float u_aspect;
void main() {
    v_color = a_color;
    v_uv = a_uv;
    vec2 pos = a_pos;
    if (u_aspect > 1.0) {
        pos.y *= u_aspect;
    } else {
        pos.x /= u_aspect;
    }
    gl_Position = vec4(pos, 0.0, 1.0);
}
"""

internal const val MESH_FRAG_SHADER = """
precision highp float;
varying vec3 v_color;
varying vec2 v_uv;
uniform sampler2D u_texture;
uniform float u_time;
uniform float u_volume;
uniform float u_alpha;

const float INV_255 = 1.0 / 255.0;
const float HALF_INV_255 = 0.5 / 255.0;
const float GRADIENT_NOISE_A = 52.9829189;
const vec2 GRADIENT_NOISE_B = vec2(0.06711056, 0.00583715);

float gradientNoise(in vec2 uv) {
    return fract(GRADIENT_NOISE_A * fract(dot(uv, GRADIENT_NOISE_B)));
}

vec2 rot(vec2 v, float angle) {
    float s = sin(angle);
    float c = cos(angle);
    return vec2(c * v.x - s * v.y, s * v.x + c * v.y);
}

void main() {
    float volumeEffect = u_volume * 2.0;
    float timeVolume = u_time + u_volume;

    float dither = INV_255 * gradientNoise(gl_FragCoord.xy) - HALF_INV_255;
    vec2 centeredUV = v_uv - vec2(0.5);
    vec2 rotatedUV = rot(centeredUV, timeVolume * 2.0);
    // 缩到内圈采样：角落最远约 0.995，永不触及纹理边界，避免 clamp 造成的接缝。
    vec2 finalUV = rotatedUV * (0.7 * max(0.001, 1.0 - volumeEffect)) + vec2(0.5);

    vec4 result = texture2D(u_texture, finalUV);

    float alphaVolumeFactor = u_alpha * max(0.5, 1.0 - u_volume * 0.5);
    result.rgb *= v_color * alphaVolumeFactor;
    result.a *= alphaVolumeFactor;

    result.rgb += vec3(dither);

    float dist = distance(v_uv, vec2(0.5));
    float vignette = smoothstep(0.8, 0.3, dist);
    float mask = 0.6 + vignette * 0.4;
    result.rgb *= mask;

    gl_FragColor = result;
}
"""

internal const val QUAD_VERT_SHADER = """
attribute vec2 a_pos;
varying vec2 v_uv;
void main() {
    gl_Position = vec4(a_pos, 0.0, 1.0);
    v_uv = a_pos * 0.5 + 0.5;
}
"""

internal const val QUAD_FRAG_SHADER = """
precision mediump float;
varying vec2 v_uv;
uniform sampler2D u_texture;
uniform float u_alpha;
void main() {
    vec4 color = texture2D(u_texture, v_uv);
    gl_FragColor = vec4(color.rgb, color.a * u_alpha);
}
"""

// ─────────────────────────── GL 程序 ───────────────────────────

internal class GLProgram(
    vertexSource: String,
    fragmentSource: String,
    private val label: String = "unknown",
) {
    val program: Int
    private val vertexShader: Int
    private val fragmentShader: Int
    val attrs: MutableMap<String, Int> = HashMap()
    private val uniformCache: MutableMap<String, Int> = HashMap()

    init {
        vertexShader = compile(GLES20.GL_VERTEX_SHADER, vertexSource)
        fragmentShader = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("Failed to link program \"$label\": $log")
        }
        val num = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_ACTIVE_ATTRIBUTES, num, 0)
        val maxLen = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_ACTIVE_ATTRIBUTE_MAX_LENGTH, maxLen, 0)
        val nameBuf = ByteArray(maxLen[0].coerceAtLeast(1))
        for (i in 0 until num[0]) {
            val len = IntArray(1); val size = IntArray(1); val type = IntArray(1)
            GLES20.glGetActiveAttrib(program, i, nameBuf.size, len, 0, size, 0, type, 0, nameBuf, 0)
            val name = String(nameBuf, 0, len[0])
            val loc = GLES20.glGetAttribLocation(program, name)
            if (loc >= 0) attrs[name] = loc
        }
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Failed to compile shader ($type) \"$label\": $log")
        }
        return shader
    }

    private fun loc(name: String): Int =
        uniformCache.getOrPut(name) { GLES20.glGetUniformLocation(program, name) }

    fun use() = GLES20.glUseProgram(program)
    fun setUniform1f(name: String, v: Float) = loc(name).let { if (it >= 0) GLES20.glUniform1f(it, v) }
    fun setUniform1i(name: String, v: Int) = loc(name).let { if (it >= 0) GLES20.glUniform1i(it, v) }

    fun dispose() {
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        GLES20.glDeleteProgram(program)
    }
}

// ─────────────────────────── 纹理 ───────────────────────────

internal class GLTexture(bitmap: Bitmap) {
    val tex: Int

    init {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        tex = ids[0]
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    fun bind() = GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
    fun dispose() = GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
}

// ─────────────────────────── 控制点 ───────────────────────────

internal class ControlPoint {
    var colorR = 1f; var colorG = 1f; var colorB = 1f
    var locX = 0f; var locY = 0f
    var uTanX = 0f; var uTanY = 0f
    var vTanX = 0f; var vTanY = 0f
    private var uRotV = 0f; private var vRotV = 0f
    private var uScaleV = 1f; private var vScaleV = 1f

    var uRot: Float
        get() = uRotV
        set(value) { uRotV = value; updateUTangent() }
    var vRot: Float
        get() = vRotV
        set(value) { vRotV = value; updateVTangent() }
    var uScale: Float
        get() = uScaleV
        set(value) { uScaleV = value; updateUTangent() }
    var vScale: Float
        get() = vScaleV
        set(value) { vScaleV = value; updateVTangent() }

    private fun updateUTangent() {
        uTanX = cos(uRotV) * uScaleV
        uTanY = sin(uRotV) * uScaleV
    }

    private fun updateVTangent() {
        vTanX = -sin(vRotV) * vScaleV
        vTanY = cos(vRotV) * vScaleV
    }
}

// ─────────────────────────── 双三次 Hermite 网格 ───────────────────────────

/** Hermite 基矩阵 H（列主序，与 gl-matrix 一致）。 */
private val H = floatArrayOf(
    2f, -2f, 1f, 1f,
    -3f, 3f, -2f, -1f,
    0f, 0f, 1f, 0f,
    1f, 0f, 0f, 0f,
)
private val H_T = FloatArray(16).also { Matrix.transposeM(it, 0, H, 0) }

/**
 * Bicubic Hermite Patch Mesh：由少量控制点通过双三次 Hermite 插值撑成的平滑变形网格。
 * 顶点布局：x, y, r, g, b, u, v（7 floats/顶点）。
 */
internal class BHPMesh(
    private val attrPos: Int,
    private val attrColor: Int,
    private val attrUV: Int,
) {
    private var vertexWidth = 0
    private var vertexHeight = 0
    private var vertexData = FloatArray(0)
    private var indexData = ShortArray(0)
    private var vertexIndexLength = 0

    private val vbo: Int
    private val ibo: Int
    private var vertexBuffer: FloatBuffer = FloatBuffer.allocate(0)
    private var indexBuffer: ShortBuffer = ShortBuffer.allocate(0)

    private var subDivisions = 10
    private var cpWidth = 3
    private var cpHeight = 3
    private var controlPoints: Array<ControlPoint> = arrayOf()

    init {
        val ids = IntArray(2)
        GLES20.glGenBuffers(2, ids, 0)
        vbo = ids[0]
        ibo = ids[1]
        resizeControlPoints(3, 3)
    }

    private fun cp(x: Int, y: Int): ControlPoint = controlPoints[x + y * cpWidth]

    fun getControlPoint(x: Int, y: Int): ControlPoint = cp(x, y)

    fun resizeControlPoints(width: Int, height: Int) {
        require(width >= 2 && height >= 2) { "Control points must be >= 2x2" }
        cpWidth = width
        cpHeight = height
        controlPoints = Array(width * height) { ControlPoint() }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val point = cp(x, y)
                point.locX = (x.toFloat() / (width - 1)) * 2f - 1f
                point.locY = (y.toFloat() / (height - 1)) * 2f - 1f
                point.uTanX = 2f / (width - 1)
                point.vTanY = 2f / (height - 1)
            }
        }
        resetSubdivision(subDivisions)
    }

    fun resetSubdivision(sd: Int) {
        subDivisions = sd
        resize((cpWidth - 1) * sd, (cpHeight - 1) * sd)
    }

    private fun resize(vw: Int, vh: Int) {
        vertexWidth = vw
        vertexHeight = vh
        vertexIndexLength = vw * vh * 6
        vertexData = FloatArray(vw * vh * 7)
        indexData = ShortArray(vertexIndexLength)

        for (y in 0 until vh) {
            for (x in 0 until vw) {
                val px = if (vw <= 1) 0f else (x.toFloat() / (vw - 1)) * 2f - 1f
                val py = if (vh <= 1) 0f else (y.toFloat() / (vh - 1)) * 2f - 1f
                setVertexData(
                    x, y, px, py, 1f, 1f, 1f,
                    if (vw <= 1) 0f else x.toFloat() / (vw - 1),
                    if (vh <= 1) 0f else y.toFloat() / (vh - 1),
                )
            }
        }
        for (y in 0 until vh - 1) {
            for (x in 0 until vw - 1) {
                val idx = (y * vw + x) * 6
                indexData[idx] = (y * vw + x).toShort()
                indexData[idx + 1] = (y * vw + x + 1).toShort()
                indexData[idx + 2] = ((y + 1) * vw + x).toShort()
                indexData[idx + 3] = (y * vw + x + 1).toShort()
                indexData[idx + 4] = ((y + 1) * vw + x + 1).toShort()
                indexData[idx + 5] = ((y + 1) * vw + x).toShort()
            }
        }

        indexBuffer = ByteBuffer.allocateDirect(indexData.size * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer()
        indexBuffer.put(indexData).position(0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES20.glBufferData(
            GLES20.GL_ELEMENT_ARRAY_BUFFER, indexData.size * 2, indexBuffer, GLES20.GL_STATIC_DRAW
        )
    }

    private fun setVertexData(
        vx: Int, vy: Int, x: Float, y: Float, r: Float, g: Float, b: Float, u: Float, v: Float,
    ) {
        val idx = (vx + vy * vertexWidth) * 7
        if (idx < 0 || idx > vertexData.size - 7) return
        vertexData[idx] = x
        vertexData[idx + 1] = y
        vertexData[idx + 2] = r
        vertexData[idx + 3] = g
        vertexData[idx + 4] = b
        vertexData[idx + 5] = u
        vertexData[idx + 6] = v
    }

    // 预分配矩阵/向量，避免频繁分配
    private val tmpX = FloatArray(16)
    private val tmpY = FloatArray(16)
    private val xAcc = FloatArray(16)
    private val yAcc = FloatArray(16)
    private val mm1 = FloatArray(16)
    private val mm2 = FloatArray(16)
    private val uPow = FloatArray(4)
    private val ux = FloatArray(4)
    private val uy = FloatArray(4)

    private fun meshCoefficients(
        p00: ControlPoint, p01: ControlPoint, p10: ControlPoint, p11: ControlPoint,
        axisX: Boolean, out: FloatArray,
    ) {
        fun l(p: ControlPoint) = if (axisX) p.locX else p.locY
        fun u(p: ControlPoint) = if (axisX) p.uTanX else p.uTanY
        fun v(p: ControlPoint) = if (axisX) p.vTanX else p.vTanY
        out[0] = l(p00); out[1] = l(p01); out[2] = v(p00); out[3] = v(p01)
        out[4] = l(p10); out[5] = l(p11); out[6] = v(p10); out[7] = v(p11)
        out[8] = u(p00); out[9] = u(p01); out[10] = 0f; out[11] = 0f
        out[12] = u(p10); out[13] = u(p11); out[14] = 0f; out[15] = 0f
    }

    /** out = H_T * (M^T * H)。 */
    private fun precompute(m: FloatArray, out: FloatArray) {
        Matrix.transposeM(mm1, 0, m, 0)          // mm1 = M^T
        Matrix.multiplyMM(mm2, 0, mm1, 0, H, 0)  // mm2 = M^T * H
        Matrix.multiplyMM(out, 0, H_T, 0, mm2, 0) // out = H_T * (M^T * H)
    }

    fun updateMesh() {
        val subDivM1 = subDivisions - 1
        val tW = subDivM1 * (cpHeight - 1)
        val tH = subDivM1 * (cpWidth - 1)
        val invSubDivM1 = if (subDivM1 == 0) 0f else 1f / subDivM1
        val invTH = if (tH == 0) 0f else 1f / tH
        val invTW = if (tW == 0) 0f else 1f / tW

        // 预计算 [norm^3, norm^2, norm, 1]
        val normPowers = FloatArray(subDivisions * 4)
        for (i in 0 until subDivisions) {
            val norm = i * invSubDivM1
            val idx = i * 4
            normPowers[idx] = norm * norm * norm
            normPowers[idx + 1] = norm * norm
            normPowers[idx + 2] = norm
            normPowers[idx + 3] = 1f
        }

        for (x in 0 until cpWidth - 1) {
            for (y in 0 until cpHeight - 1) {
                val p00 = cp(x, y)
                val p01 = cp(x, y + 1)
                val p10 = cp(x + 1, y)
                val p11 = cp(x + 1, y + 1)

                meshCoefficients(p00, p01, p10, p11, true, tmpX)
                meshCoefficients(p00, p01, p10, p11, false, tmpY)
                precompute(tmpX, xAcc)
                precompute(tmpY, yAcc)

                val sX = x.toFloat() / (cpWidth - 1)
                val sY = y.toFloat() / (cpHeight - 1)
                val baseVx = y * subDivisions
                val baseVy = x * subDivisions

                for (u in 0 until subDivisions) {
                    val vxOffset = baseVx + u
                    val uIdx = u * 4
                    uPow[0] = normPowers[uIdx]
                    uPow[1] = normPowers[uIdx + 1]
                    uPow[2] = normPowers[uIdx + 2]
                    uPow[3] = normPowers[uIdx + 3]
                    Matrix.multiplyMV(ux, 0, xAcc, 0, uPow, 0)
                    Matrix.multiplyMV(uy, 0, yAcc, 0, uPow, 0)

                    for (v in 0 until subDivisions) {
                        val vy = baseVy + v
                        val vIdx = v * 4
                        val v0 = normPowers[vIdx]
                        val v1 = normPowers[vIdx + 1]
                        val v2 = normPowers[vIdx + 2]
                        val v3 = normPowers[vIdx + 3]

                        val px = v0 * ux[0] + v1 * ux[1] + v2 * ux[2] + v3 * ux[3]
                        val py = v0 * uy[0] + v1 * uy[1] + v2 * uy[2] + v3 * uy[3]
                        val uvX = sX + v * invTH
                        val uvY = 1f - sY - u * invTW
                        setVertexData(vxOffset, vy, px, py, 1f, 1f, 1f, uvX, uvY)
                    }
                }
            }
        }
        upload()
    }

    private fun upload() {
        vertexBuffer = ByteBuffer.allocateDirect(vertexData.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        vertexBuffer.put(vertexData).position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER, vertexData.size * 4, vertexBuffer, GLES20.GL_DYNAMIC_DRAW
        )
    }

    fun bind() {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo)
        val stride = 4 * 7
        if (attrPos >= 0) {
            GLES20.glVertexAttribPointer(attrPos, 2, GLES20.GL_FLOAT, false, stride, 0)
            GLES20.glEnableVertexAttribArray(attrPos)
        }
        if (attrColor >= 0) {
            GLES20.glVertexAttribPointer(attrColor, 3, GLES20.GL_FLOAT, false, stride, 4 * 2)
            GLES20.glEnableVertexAttribArray(attrColor)
        }
        if (attrUV >= 0) {
            GLES20.glVertexAttribPointer(attrUV, 2, GLES20.GL_FLOAT, false, stride, 4 * 5)
            GLES20.glEnableVertexAttribArray(attrUV)
        }
    }

    fun draw() {
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, vertexIndexLength, GLES20.GL_UNSIGNED_SHORT, 0)
    }

    fun dispose() {
        GLES20.glDeleteBuffers(2, intArrayOf(vbo, ibo), 0)
    }
}
