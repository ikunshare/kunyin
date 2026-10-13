package com.ikunshare.music.ui.screens.player

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.ikunshare.music.model.MusicItem
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Song the player was opened for; mirrors the old `player/{songId}?songKey=` arguments. */
data class PlayerRequest(val songId: Long, val songKey: String)

/**
 * The full-screen player is presented as a sheet over the app rather than a navigation
 * destination, so the tabs stay composed underneath and can follow the dismiss gesture.
 */
@Stable
class PlayerSheetState internal constructor(open: Boolean, request: PlayerRequest?) {
    /** 0 = collapsed, 1 = fully presented. Only read from layout/draw lambdas. */
    internal val progress = Animatable(if (open) 1f else 0f)

    var isOpen by mutableStateOf(open)
        private set

    var request by mutableStateOf(request)
        private set

    internal var settleVelocity = 0f
    internal var settleRequest by mutableIntStateOf(0)
        private set

    /** True while any part of the sheet is on screen. */
    val isVisible: Boolean by derivedStateOf { isOpen || progress.value > 0f }

    /** True once the sheet covers the whole screen and is at rest. */
    val isSettledOpen: Boolean by derivedStateOf { isOpen && progress.value >= 1f && !progress.isRunning }

    fun open(song: MusicItem) = open(song.id, song.uniqueKey)

    fun open(songId: Long, songKey: String = "") {
        request = PlayerRequest(songId, songKey)
        settle(true)
    }

    fun close() = settle(false)

    /** [velocity] is in progress-units per second (positive = towards open). */
    internal fun settle(open: Boolean, velocity: Float = 0f) {
        settleVelocity = velocity
        isOpen = open
        settleRequest++
    }

    companion object {
        val Saver: Saver<PlayerSheetState, Any> = Saver(
            save = { listOf(it.isOpen, it.request?.songId ?: 0L, it.request?.songKey.orEmpty(), it.request != null) },
            restore = {
                val values = it as List<*>
                val request = if (values[3] as Boolean) PlayerRequest(values[1] as Long, values[2] as String) else null
                PlayerSheetState(values[0] as Boolean && request != null, request)
            }
        )
    }
}

@Composable
fun rememberPlayerSheetState(): PlayerSheetState =
    rememberSaveable(saver = PlayerSheetState.Saver) { PlayerSheetState(false, null) }

/** Hosts without a sheet (previews, debug harnesses) get a detached state. */
val LocalPlayerSheet = staticCompositionLocalOf { PlayerSheetState(false, null) }

private const val DISMISS_VELOCITY = 1.6f // screen heights per second
private const val BACKGROUND_SCALE = 0.92f
private const val BACKGROUND_DIM = 0.42f
private const val BACK_GESTURE_TRAVEL = 0.22f

/**
 * Hosts [content] (the whole app) and the player [sheet]. The app recedes like an
 * iOS card while the player rises; dragging down or a back gesture tracks the finger.
 */
@Composable
fun PlayerSheetHost(
    state: PlayerSheetState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
    sheet: @Composable (request: PlayerRequest) -> Unit
) {
    val scope = rememberCoroutineScope()
    val corner = 28.dp
    var heightPx by remember { mutableFloatStateOf(0f) }
    val progress = state.progress

    LaunchedEffect(state.settleRequest) {
        if (state.settleRequest == 0) return@LaunchedEffect
        val target = if (state.isOpen) 1f else 0f
        val velocity = state.settleVelocity
        state.settleVelocity = 0f
        progress.animateTo(
            target,
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = if (state.isOpen) 260f else 340f,
                visibilityThreshold = 0.0005f
            ),
            initialVelocity = velocity
        )
    }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { heightPx = it.height.toFloat() }
            // The receding card sits on black, as on iOS; nothing to draw while collapsed.
            .drawBehind { if (progress.value > 0f) drawRect(Color.Black) }
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = progress.value.coerceIn(0f, 1f)
                    // Fully covered: skip compositing the app beneath the opaque player.
                    alpha = if (p >= 1f) 0f else 1f
                    val scale = lerp(1f, BACKGROUND_SCALE, p)
                    scaleX = scale
                    scaleY = scale
                    if (p > 0f) {
                        shape = RoundedRectangle(corner * minOf(1f, p * 4f))
                        clip = true
                    } else {
                        shape = RectangleShape
                        clip = false
                    }
                }
                .then(if (state.isOpen) Modifier.semantics { hideFromAccessibility() } else Modifier)
        ) {
            content()
            // Dimming scrim; drawn only while the sheet is partially visible.
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = progress.value.coerceIn(0f, 1f) * BACKGROUND_DIM
                    }
                    .drawBehind { drawRect(Color.Black) }
            )
        }

        val request = state.request
        if (state.isVisible && request != null) {
            PlayerSheetSurface(state, heightPx, corner, onDrag = { delta ->
                if (heightPx > 0f) scope.launch {
                    progress.snapTo((progress.value - delta / heightPx).coerceIn(0f, 1f))
                }
            }, onDragStopped = { velocityPx ->
                val velocity = if (heightPx > 0f) -velocityPx / heightPx else 0f
                val dismiss = velocity < -DISMISS_VELOCITY || (progress.value < 0.72f && velocity < 0.4f)
                state.settle(!dismiss, velocity)
            }) {
                sheet(request)
            }
        }
    }
}

@Composable
private fun PlayerSheetSurface(
    state: PlayerSheetState,
    heightPx: Float,
    corner: Dp,
    onDrag: (Float) -> Unit,
    onDragStopped: (Float) -> Unit,
    content: @Composable () -> Unit
) {
    val progress = state.progress

    PredictiveBackHandler(enabled = state.isOpen) { events ->
        try {
            events.collect { event ->
                progress.snapTo(1f - event.progress * BACK_GESTURE_TRAVEL)
            }
            state.settle(false)
        } catch (cancelled: CancellationException) {
            state.settle(true)
            throw cancelled
        }
    }

    // Lists and pagers hand their overscroll to the sheet, so pulling down from the
    // top of the cover page or lyrics dismisses, and pushing back up re-expands it.
    val connection = remember(state) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y >= 0f) return Offset.Zero
                if (progress.value >= 1f || !state.isOpen) return Offset.Zero
                onDrag(available.y)
                return Offset(0f, available.y)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y <= 0f || !state.isOpen) return Offset.Zero
                onDrag(available.y)
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (progress.value >= 1f || !state.isOpen) return Velocity.Zero
                onDragStopped(available.y)
                return available
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val p = progress.value
                translationY = (1f - p).coerceAtLeast(0f) * heightPx
                if (p < 1f) {
                    shape = RoundedRectangle(corner)
                    clip = true
                } else {
                    shape = RectangleShape
                    clip = false
                }
            }
            .nestedScroll(connection)
            .draggable(
                state = rememberDraggableState { delta -> onDrag(delta) },
                orientation = Orientation.Vertical,
                enabled = state.isOpen,
                onDragStopped = { velocity -> onDragStopped(velocity) }
            )
    ) {
        content()
    }
}
