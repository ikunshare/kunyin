package com.ikunshare.music.ui.screens.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Original 24 px line icons: paired captions, syllable waveform, and spoken caption. */
internal object PlayerLyricIcons {
    val Translation = lyricIcon("LyricTranslation") {
        // Two caption lines exchange language through the arrows at either side.
        moveTo(7f, 5f); lineTo(17f, 5f)
        moveTo(7f, 8.5f); lineTo(13f, 8.5f)
        moveTo(7f, 15.5f); lineTo(17f, 15.5f)
        moveTo(11f, 19f); lineTo(17f, 19f)
        moveTo(3f, 7f); lineTo(3f, 17f); lineTo(5f, 15f)
        moveTo(3f, 17f); lineTo(1f, 15f)
        moveTo(21f, 17f); lineTo(21f, 7f); lineTo(19f, 9f)
        moveTo(21f, 7f); lineTo(23f, 9f)
    }

    val Romanization = lyricIcon("LyricRomanization") {
        // A sound waveform with three syllable marks below it.
        moveTo(3f, 10f); lineTo(6f, 10f); lineTo(8f, 5f)
        lineTo(11f, 15f); lineTo(14f, 3f); lineTo(17f, 10f); lineTo(21f, 10f)
        moveTo(4f, 20f); lineTo(7f, 20f)
        moveTo(10.5f, 20f); lineTo(13.5f, 20f)
        moveTo(17f, 20f); lineTo(20f, 20f)
    }

    val Phonetic = lyricIcon("LyricPhonetic") {
        moveTo(6f, 4f); lineTo(18f, 4f)
        quadTo(21f, 4f, 21f, 7f); lineTo(21f, 14f)
        quadTo(21f, 17f, 18f, 17f); lineTo(10f, 17f)
        lineTo(5f, 21f); lineTo(5f, 17f)
        quadTo(3f, 17f, 3f, 14f); lineTo(3f, 7f)
        quadTo(3f, 4f, 6f, 4f); close()
        moveTo(7f, 10.5f); lineTo(9f, 10.5f); lineTo(10.5f, 7.5f)
        lineTo(13f, 13.5f); lineTo(15f, 10.5f); lineTo(17f, 10.5f)
    }
}

private fun lyricIcon(name: String, draw: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f).apply {
        path(fill = null, stroke = SolidColor(Color.White), strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = draw)
    }.build()
