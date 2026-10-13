package com.ikunshare.music.utils

import android.app.Activity
import android.content.pm.ActivityInfo

/**
 * Small-screen devices (phones, folded foldables) are always portrait; the player and
 * other layouts are not designed for a phone held sideways. Large screens rotate freely.
 */
object OrientationPolicy {
    const val LARGE_SCREEN_MIN_WIDTH_DP = 600

    fun apply(activity: Activity) {
        val target = if (activity.resources.configuration.smallestScreenWidthDp >= LARGE_SCREEN_MIN_WIDTH_DP) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        }
        if (activity.requestedOrientation != target) activity.requestedOrientation = target
    }
}
