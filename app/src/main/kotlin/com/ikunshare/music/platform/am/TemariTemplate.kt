/*
 * MIT License
 *
 * Copyright (c) 2025 Temari contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.ikunshare.music.platform.am

import com.google.gson.JsonObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

data class TemariRegisters(
    val rdx: Int,
    val rcx: Int,
    val rax: Int,
    val r9: Int,
    val rbp: Int
)

data class TemariTemplateData(
    val context: ByteArray,
    val state: IntArray,
    val registers: TemariRegisters
)

internal val TEMARI_STATE_SLOTS = intArrayOf(
    0x30, 0x40, 0x48, 0x50, 0x58, 0x5a, 0x60, 0x68, 0x70, 0x72, 0x78, 0x80, 0x88, 0x90, 0x96,
    0x98, 0xa0, 0xb0, 0xc0, 0xc8, 0xd0, 0xd8, 0xe0, 0xe8, 0xf0, 0xf8, 0x100, 0x104, 0x108,
    0x112, 0x118, 0x120, 0x130, 0x144, 0x150, 0x152, 0x158, 0x160, 0x168, 0x170, 0x178,
    0x180, 0x188, 0x190, 0x192, 0x198, 0x1a0, 0x1a8, 0x1b0, 0x1b8, 0x1c0, 0x200, 0x208,
    0x210, 0x216, 0x218, 0x220, 0x224, 0x228, 0x230, 0x232, 0x238, 0x240, 0x248, 0x250,
    0x256, 0x258, 0x260, 0x264, 0x268, 0x270, 0x280, 0x288, 0x290, 0x298, 0x2a0, 0x2a8,
    0x2b0, 0x2b8, 0x2c0, 0x2c8, 0x2d0, 0x2d8, 0x2e0, 0x2e8, 0x2f0, 0x2f8, 0x300, 0x304,
    0x308, 0x310, 0x318, 0x320, 0x328, 0x330, 0x336, 0x338, 0x340, 0x344, 0x348, 0x350,
    0x352, 0x358, 0x360, 0x368, 0x370, 0x376, 0x378, 0x380, 0x384, 0x388, 0x390, 0x392,
    0x398, 0x3b0, 0x3b8, 0x3c0, 0x3c8, 0x3d0, 0x3e0, 0x3e8, 0x3f8, 0x400, 0x408, 0x410,
    0x416, 0x418, 0x420, 0x424, 0x428, 0x430, 0x432, 0x440, 0x448, 0x450, 0x460, 0x468,
    0x470, 0x478, 0x480, 0x488, 0x490, 0x4d8, 0x4e0, 0x4e8, 0x4f0, 0x4f8, 0x500, 0x508,
    0x510, 0x512, 0x518, 0x520, 0x528, 0x530, 0x536, 0x538, 0x540, 0x548, 0x550, 0x552,
    0x558, 0x560, 0x568, 0x570, 0x576, 0x578, 0x580, 0x584, 0x588, 0x590, 0x592, 0x598,
    0x5a0, 0x5a8, 0x5b0, 0x5b8, 0x5c0, 0x5c8, 0x5d8, 0x5e8, 0x5f0, 0x5f8, 0x600, 0x608,
    0x610, 0x616, 0x618, 0x620, 0x624, 0x628, 0x638, 0x640, 0x648, 0x656, 0x658, 0x660,
    0x664, 0x668, 0x670, 0x672, 0x678, 0x680, 0x688, 0x690, 0x696, 0x698, 0x6a8, 0x6b0,
    0x6b8, 0x6c0, 0x6c8, 0x6d8, 0x6e0, 0x6e8, 0x6f0, 0x6f8, 0x700, 0x708, 0x720, 0x728,
    0x736, 0x740, 0x752, 0x760, 0x768, 0x770, 0x778, 0x780, 0x792, 0x816, 0x824, 0x872,
    0x912, 0x944, 0x952, 0x960, 0x992, 0x1040, 0x1088, 0x1328, 0x1360, 0x1368, 0x1384,
    0x1408, 0x1416, 0x1424, 0x1432, 0x1440, 0x1448, 0x1456, 0x1480, 0x1512, 0x1528,
    0x1536, 0x1552, 0x1560, 0x1568, 0x1576, 0x1632, 0x1704, 0x1720, 0x1760
)

@OptIn(ExperimentalEncodingApi::class)
fun parseTemariTemplate(json: JsonObject): TemariTemplateData {
    val context = json.base64Field("ctx")
    val stateBytes = json.base64Field("state")
    require(context.size >= 0x8000) { "Temari ctx 过短: ${context.size}" }
    require(stateBytes.size >= 0x2000) { "Temari state 过短: ${stateBytes.size}" }
    val state = IntArray(2048)
    for (offset in TEMARI_STATE_SLOTS) {
        if (offset < state.size) state[offset] = littleEndian(stateBytes, 0x2000 - offset)
    }
    return TemariTemplateData(context, state, TemariRegisters(
        rdx = json.register("rdx"),
        rcx = json.register("rcx"),
        rax = json.register("rax"),
        r9 = json.register("r9"),
        rbp = json.register("rbp")
    ))
}

private fun littleEndian(data: ByteArray, offset: Int): Int =
    (data[offset].toInt() and 0xff) or
        (data[offset + 1].toInt() and 0xff shl 8) or
        (data[offset + 2].toInt() and 0xff shl 16) or
        (data[offset + 3].toInt() and 0xff shl 24)

@OptIn(ExperimentalEncodingApi::class)
private fun JsonObject.base64Field(name: String): ByteArray {
    val value = get(name)
    require(value?.isJsonPrimitive == true) { "Temari 模板缺少 $name" }
    val filtered = buildString(value.asString.length) {
        for (character in value.asString) {
            if (character == '=') break
            if (character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9' ||
                character == '+' || character == '/') append(character)
        }
    }
    val length = filtered.length - if (filtered.length % 4 == 1) 1 else 0
    val normalized = filtered.substring(0, length)
        .padEnd(length + (4 - length % 4) % 4, '=')
    return try {
        Base64.decode(normalized)
    } catch (error: IllegalArgumentException) {
        throw IllegalArgumentException("Temari $name 不是有效 Base64", error)
    }
}

private fun JsonObject.register(name: String): Int {
    val value = get(name) ?: return 0
    require(value.isJsonPrimitive) { "Temari 寄存器 $name 无效" }
    var text = value.asString.trim()
    if (text.startsWith("0x")) text = text.substring(2)
    if (text.startsWith("+")) text = text.substring(1)
    require(text.isNotEmpty()) { "Temari 寄存器 $name 无效" }
    var result = 0L
    for (character in text) {
        val digit = when (character) {
            in '0'..'9' -> character - '0'
            in 'a'..'f' -> character - 'a' + 10
            in 'A'..'F' -> character - 'A' + 10
            else -> -1
        }
        require(digit >= 0 && result ushr 60 == 0L) { "Temari 寄存器 $name 无效" }
        result = result * 16 + digit
    }
    return result.toInt()
}
