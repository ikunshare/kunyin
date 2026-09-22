package com.ikunshare.sound.ui.utils

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 外层悬浮底栏（MiniPlayer + 导航栏）占用的预估高度，用于子页面浮层避让 */
val LocalFloatingBottomBarReserve = staticCompositionLocalOf<Dp> { 0.dp }
