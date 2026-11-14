package com.ikunshare.music.ui.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import com.ikunshare.music.model.MusicItem

internal fun Modifier.onPointerCancel(onCancel: () -> Unit): Modifier = composed {
    val currentOnCancel by rememberUpdatedState(onCancel)
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val cancelled = event.changes.none { it.pressed } && event.changes.any {
                    it.previousPressed && !it.pressed && !it.changedToUp()
                }
                if (cancelled) currentOnCancel()
            }
        }
    }
}

/** 监听按压事件（Initial pass），用于在列表项按下时定位悬浮菜单。 */
internal suspend fun PointerInputScope.onPress(block: (PointerEvent) -> Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Press) block(event)
        }
    }
}

/** 选择模式下用于 O(1) 判断某首歌是否被选中。 */
@Composable
internal fun rememberSelectedKeys(selectedSongs: List<MusicItem>): State<Set<String>> =
    remember(selectedSongs) { derivedStateOf { selectedSongs.map { it.uniqueKey }.toHashSet() } }
