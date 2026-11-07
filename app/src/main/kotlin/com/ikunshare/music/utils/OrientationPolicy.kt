package com.ikunshare.music.utils

import android.app.Activity
import android.content.pm.ActivityInfo

/** Follow the system orientation on phones, tablets, and foldables. */
object OrientationPolicy {
    fun apply(activity: Activity) {
        if (activity.requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}
