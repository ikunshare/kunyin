package com.ikunshare.music.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer

/**
 * 应用内统一的 Markdown 渲染：支持 `![alt](url)` 图片（Coil 加载，按容器宽度等比缩放）。
 */
@Composable
fun AppMarkdown(content: String, modifier: Modifier = Modifier) {
    Markdown(
        content = content,
        modifier = modifier,
        imageTransformer = RoundedImageTransformer
    )
}

/** 在 Coil3 实现基础上给图片加圆角 */
private object RoundedImageTransformer : ImageTransformer {
    private val shape = RoundedCornerShape(12.dp)

    @Composable
    override fun transform(link: String): ImageData =
        Coil3ImageTransformerImpl.transform(link).copy(modifier = Modifier.clip(shape))

    @Composable
    override fun intrinsicSize(painter: Painter): Size =
        Coil3ImageTransformerImpl.intrinsicSize(painter)
}
