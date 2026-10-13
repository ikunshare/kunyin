package com.ikunshare.music.utils

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri

/**
 * Background playback needs the app exempt from battery optimization, otherwise many ROMs
 * freeze the process shortly after the screen turns off. Asked once per launch until granted.
 */
object BatteryOptimization {
    @Volatile
    private var requestedThisProcess = false

    fun isIgnoring(activity: Activity): Boolean {
        val pm = activity.getSystemService(PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(activity.packageName)
    }

    @SuppressLint("BatteryLife")
    fun requestIfNeeded(activity: Activity) {
        if (requestedThisProcess || isIgnoring(activity)) return
        requestedThisProcess = true
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            "package:${activity.packageName}".toUri()
        )
        runCatching { activity.startActivity(direct) }.onFailure {
            // 部分 ROM 不允许带 data 直跳，退回系统电池优化列表页
            runCatching { activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }
}
