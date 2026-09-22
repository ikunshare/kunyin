package com.ikunshare.sound.ui.utils

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 滚动状态上报函数类型
 * @param firstVisibleItemIndex 当前首个可见项索引
 * @param firstVisibleItemScrollOffset 当前首个可见项偏移量（px 单位）
 * @param isScrollingUp 当前是否向上滚动（true 表示向上）
 */
typealias ScrollOffsetReporter = (
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    isScrollingUp: Boolean
) -> Unit

/**
 * 滚动状态提供者 CompositionLocal
 */
val LocalScrollOffsetReporter = staticCompositionLocalOf<ScrollOffsetReporter?> {
    null
}
