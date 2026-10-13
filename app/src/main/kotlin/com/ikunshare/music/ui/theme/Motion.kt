package com.ikunshare.music.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * App-wide motion tokens. Springs are tuned after the iOS 26 defaults
 * (`.smooth` / `.snappy` / `.bouncy`) so glass surfaces feel physical
 * instead of following fixed-duration curves.
 */
object Motion {
    /** Critically damped; page pushes, sheets and other large surfaces. */
    const val SMOOTH_STIFFNESS = 320f

    /** Quick settle for small controls and state toggles. */
    const val SNAPPY_STIFFNESS = 700f

    /** iOS "emphasized" curve: fast start, long soft landing. */
    val EmphasizedEasing: Easing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

    /**
     * For transitions that a back gesture seeks: nearly linear where the finger usually is,
     * so the page tracks 1:1, easing out only near the end.
     */
    val TrackingEasing: Easing = CubicBezierEasing(0.25f, 0.25f, 0.4f, 1f)
    const val TRACKING_DURATION_MS = 380

    /** How far a covered page drifts while another page is pushed over it (iOS parallax). */
    const val PARALLAX_FRACTION = 0.3f

    /** Gentle ease for fades that accompany spring motion. */
    val FadeEasing: Easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

    fun <T> smooth(visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
        spring(Spring.DampingRatioNoBouncy, SMOOTH_STIFFNESS, visibilityThreshold)

    fun <T> snappy(visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
        spring(0.86f, SNAPPY_STIFFNESS, visibilityThreshold)

    fun <T> bouncy(visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
        spring(0.72f, 420f, visibilityThreshold)

    val smoothOffset: FiniteAnimationSpec<IntOffset> = smooth(IntOffset.VisibilityThreshold)
    val smoothSize: FiniteAnimationSpec<IntSize> = smooth(IntSize.VisibilityThreshold)
    val snappySize: FiniteAnimationSpec<IntSize> = snappy(IntSize.VisibilityThreshold)

    fun <T> tracking(): FiniteAnimationSpec<T> = tween(TRACKING_DURATION_MS, easing = TrackingEasing)

    fun <T> fadeIn(durationMillis: Int = 220, delayMillis: Int = 0): FiniteAnimationSpec<T> =
        tween(durationMillis, delayMillis, FadeEasing)

    fun <T> fadeOut(durationMillis: Int = 160): FiniteAnimationSpec<T> =
        tween(durationMillis, easing = FadeEasing)
}
