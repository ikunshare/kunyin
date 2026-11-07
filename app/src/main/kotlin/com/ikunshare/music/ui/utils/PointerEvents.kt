package com.ikunshare.music.ui.utils

import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput

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
