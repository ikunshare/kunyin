package com.ikunshare.music.ui.screens.player

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

@Stable
internal class PlayerControlsVisibility {
    var visible by mutableStateOf(true)
        private set
    var touching by mutableStateOf(false)
    var menuOpen by mutableStateOf(false)
    var interaction by mutableIntStateOf(0)
        private set

    fun show() {
        visible = true
        interaction++
    }

    fun releaseTouch() {
        touching = false
        interaction++
    }

    fun hide() { visible = false }
}

@Composable
internal fun rememberPlayerControlsVisibility(
    songKey: String?, holdOpen: Boolean, autoHideEnabled: Boolean
): PlayerControlsVisibility {
    val controls = remember { PlayerControlsVisibility() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle, controls) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> { resumed = true; controls.show() }
                Lifecycle.Event.ON_PAUSE -> { resumed = false; controls.releaseTouch() }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(songKey, autoHideEnabled) { controls.show() }
    LaunchedEffect(holdOpen, controls.menuOpen) {
        if (holdOpen || controls.menuOpen) controls.show()
    }
    LaunchedEffect(controls.interaction, controls.touching, controls.menuOpen, holdOpen, resumed, autoHideEnabled) {
        if (autoHideEnabled && resumed && !controls.touching && !controls.menuOpen && !holdOpen) {
            delay(4_000)
            controls.hide()
        }
    }
    return controls
}

/** Observe gestures without taking horizontal paging or vertical lyric scrolling away from children. */
internal fun Modifier.playerControlsGestures(
    controls: PlayerControlsVisibility, autoHideEnabled: Boolean
): Modifier = pointerInput(controls, autoHideEnabled) {
    val slop = viewConfiguration.touchSlop
    try {
        awaitPointerEventScope {
            var gestureActive = false
            var wakeOnly = false
            var origin = Offset.Zero
            var moved = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.any { it.pressed }
                if (!gestureActive && pressed) {
                    gestureActive = true
                    wakeOnly = autoHideEnabled && !controls.visible
                    origin = event.changes.first { it.pressed }.position
                    moved = false
                    controls.touching = true
                }
                if (gestureActive) {
                    if (event.changes.size > 1 || event.changes.any { (it.position - origin).getDistance() > slop }) moved = true
                    // A hidden player's first tap only reveals controls, never seeks or presses a song action.
                    // Scrollables can still start from a consumed down and receive all subsequent movement.
                    if (wakeOnly) event.changes.filter { it.changedToDownIgnoreConsumed() }.forEach { it.consume() }
                    if (!pressed) {
                        if (wakeOnly && !moved) {
                            event.changes.filter { it.changedToUpIgnoreConsumed() }.forEach { it.consume() }
                            controls.show()
                        }
                        gestureActive = false
                        controls.releaseTouch()
                    }
                }
            }
        }
    } finally {
        controls.releaseTouch()
    }
}
