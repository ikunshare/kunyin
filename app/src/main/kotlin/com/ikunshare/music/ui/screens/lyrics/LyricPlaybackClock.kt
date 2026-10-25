package com.ikunshare.music.ui.screens.lyrics

import kotlin.math.exp

/**
 * 歌词帧时钟：在播放器的稀疏位置采样（约 200ms 一次）之间逐帧插值，并平滑吸收采样抖动。
 *
 * 采样本身带 10~20ms 的时间误差（主线程轮询时机、交付到组合的延迟、音频时钟步进）。
 * 若每次采样都硬对齐，擦除边缘每秒会顿挫或跳动好几次；这里改为锁相跟踪：
 * - 每帧按 vsync 间隔 × 倍速推进，再把与采样推算值的误差按时间常数 [SMOOTH_MS] 逐步吸收
 * - 单帧推进量限制在正常速度的 0.5~1.5 倍之间，时间永远单调前进，不会停顿或倒退
 * - 误差超过跳转阈值（拖动进度、点歌词跳转、缓冲恢复）才直接跳到采样值
 */
internal class LyricPlaybackClock(initialPosition: Long, nowNanos: Long) {
    private var rendered = initialPosition.toDouble()
    private var anchorPosition = initialPosition.toDouble()
    private var anchorNanos = nowNanos
    private var lastFrameNanos = nowNanos
    private var running = false

    /** 收到一次播放器位置采样。暂停时直接对齐；播放中只更新锚点，由 [frame] 平滑追赶。 */
    fun accept(position: Long, nowNanos: Long, running: Boolean): Long {
        anchorPosition = position.toDouble()
        anchorNanos = nowNanos
        val error = position - rendered
        if (!running || !this.running || error > FORWARD_JUMP_MS || error < -BACKWARD_JUMP_MS) {
            rendered = position.toDouble()
            lastFrameNanos = nowNanos
        }
        this.running = running
        return rendered.toLong()
    }

    /** 每帧调用；[frameNanos] 取 vsync 时间戳，使每帧推进量均匀。 */
    fun frame(frameNanos: Long, playbackRate: Float): Long {
        if (!running) return rendered.toLong()
        val dtMs = ((frameNanos - lastFrameNanos) / 1e6).coerceIn(0.0, MAX_FRAME_MS)
        if (frameNanos > lastFrameNanos) lastFrameNanos = frameNanos
        val rate = playbackRate.toDouble()
        val truth = anchorPosition + (frameNanos - anchorNanos) / 1e6 * rate
        val advance = dtMs * rate
        // 先按正常速度推进，再与采样推算值比较，误差里不含本帧推进量
        val error = truth - (rendered + advance)
        if (error > FORWARD_JUMP_MS || error < -BACKWARD_JUMP_MS) {
            rendered = truth
            return rendered.toLong()
        }
        val correction = error * (1.0 - exp(-dtMs / SMOOTH_MS))
        rendered += (advance + correction).coerceIn(advance * 0.5, advance * 1.5)
        return rendered.toLong()
    }

    private companion object {
        /** 误差吸收时间常数：远大于采样抖动周期，远小于人能察觉的不同步。 */
        const val SMOOTH_MS = 250.0
        /** 超前于采样这么多才直接跳（拖动进度 / 缓冲恢复）。 */
        const val FORWARD_JUMP_MS = 300.0
        /** 落后于采样（向后跳转）超过这么多就直接跳，避免倒带被平滑成慢放。 */
        const val BACKWARD_JUMP_MS = 120.0
        /** 单帧最大推进间隔（后台恢复 / 掉帧时不一次冲过头，由误差修正接管）。 */
        const val MAX_FRAME_MS = 100.0
    }
}
