package com.kyant.shapes

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape

/**
 * 临时 shim：原生 com.kyant:shapes 库尚未引入。
 * 使用 RoundedCornerShape(50) 提供胶囊形状作为占位。
 */
@Suppress("FunctionName")
fun Capsule(): Shape = RoundedCornerShape(50)
