package com.ikunshare.music.ui.utils

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 外层悬浮底栏（MiniPlayer + 导航栏）占用的预估高度，用于子页面浮层避让 */
// Dynamic: the reserve changes with compact mode, which must not recompose every page.
val LocalFloatingBottomBarReserve = compositionLocalOf<Dp> { 0.dp }
