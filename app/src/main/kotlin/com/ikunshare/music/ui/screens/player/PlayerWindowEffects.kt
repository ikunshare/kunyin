package com.ikunshare.music.ui.screens.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Keep the visible player awake; restore the host window when the player is closed.
 * [active] turns false as soon as a dismiss starts, so the system bars are back before
 * the content underneath is revealed (the player itself lays out with stable insets).
 */
@Composable
internal fun PlayerWindowEffects(active: Boolean = true) {
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(view, lifecycle, active) {
        val window = view.context.playerActivity()?.window
        if (window == null || !active) return@DisposableEffect onDispose { }
        val keepScreenOn = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        val wasKeepingScreenOn = window.attributes.flags and keepScreenOn != 0
        val controller = WindowCompat.getInsetsController(window, view)
        val previousBehavior = controller.systemBarsBehavior
        val previousInsets = ViewCompat.getRootWindowInsets(view)
        val statusWasVisible = previousInsets?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: true
        val navigationWasVisible = previousInsets?.isVisible(WindowInsetsCompat.Type.navigationBars()) ?: true

        fun restoreKeepScreenOn() {
            if (wasKeepingScreenOn) window.addFlags(keepScreenOn) else window.clearFlags(keepScreenOn)
        }
        fun applyPlayerWindow() {
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
            window.addFlags(keepScreenOn)
            // BEHAVIOR_DEFAULT: with gesture navigation the back swipe works while the bars are hidden.
            // TRANSIENT_BARS_BY_SWIPE spent the first back swipe on revealing the bars, so exiting took two.
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            controller.hide(WindowInsetsCompat.Type.statusBars())
            // Button navigation has no gesture to fall back on: keep its back button one tap away.
            if (usesButtonNavigation(view)) controller.show(WindowInsetsCompat.Type.navigationBars())
            else controller.hide(WindowInsetsCompat.Type.navigationBars())
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> applyPlayerWindow()
                Lifecycle.Event.ON_PAUSE -> restoreKeepScreenOn()
                else -> Unit
            }
        }
        // Also reapply after returning from a dialog or another app.
        val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) applyPlayerWindow()
        }
        lifecycle.addObserver(observer)
        view.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
        applyPlayerWindow()
        onDispose {
            lifecycle.removeObserver(observer)
            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener)
            restoreKeepScreenOn()
            controller.systemBarsBehavior = previousBehavior
            if (statusWasVisible) controller.show(WindowInsetsCompat.Type.statusBars())
            else controller.hide(WindowInsetsCompat.Type.statusBars())
            if (navigationWasVisible) controller.show(WindowInsetsCompat.Type.navigationBars())
            else controller.hide(WindowInsetsCompat.Type.navigationBars())
        }
    }
}

/**
 * Button (2/3-key) navigation reports a tappable navigation bar; gesture navigation only reserves
 * gesture space. Status bar insets are tappable too, so only the bottom and side edges count.
 */
private fun usesButtonNavigation(view: View): Boolean {
    val insets = ViewCompat.getRootWindowInsets(view) ?: return false
    val tappable = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.tappableElement())
    return tappable.bottom > 0 || tappable.left > 0 || tappable.right > 0
}

private tailrec fun Context.playerActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.playerActivity()
    else -> null
}
