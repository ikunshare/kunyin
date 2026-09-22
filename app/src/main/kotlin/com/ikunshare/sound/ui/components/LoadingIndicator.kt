package com.ikunshare.sound.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * 加载指示器组件
 *
 * 在数据加载过程中显示居中的圆形进度指示器。
 * 用于搜索加载、歌单加载、歌词加载等场景。
 *
 * Requirements:
 * - 6.9: 当 Provider 数据加载时，显示适当的加载指示器
 *
 * @param modifier 修饰符
 */
@Composable
fun LoadingIndicator(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.tertiary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}
