package com.ikunshare.music.ui.screens.lyrics

import kotlin.math.exp

/**
 * 歌词帧时钟：在播放器的稀疏位置采样（约 200ms 一次）之间逐帧插值，并平滑吸收采样抖动。
 *
 * 采样本身带 10~20ms 的时间误差（主线程轮询时机、交付到组合的延迟、音频时钟步进）。
 * 若每次采样都硬对齐，擦除边缘每秒会顿挫或跳动好几次；这里改为锁相跟踪：
 * - 每帧按 vsync 间隔 × 倍速推进，再把与采样推算值的误差按时间常数 [SMOOTH_MS] 逐步吸收
 * - 落后时以 0.5~1.5 倍速追赶；超前时允许减速至接近停顿，但单帧不倒退
 * - 大跨度采样跳转或明确 seek 直接对齐，普通采样误差仍平滑吸收
 */
internal class LyricPlaybackClock(initialPosition: Long, nowNanos: Long) {
    private var rendered = initialPosition.toDouble()
    private var anchorPosition = initialPosition.toDouble()
    private var anchorNanos = nowNanos
    private var lastFrameNanos = nowNanos
    private var running = false

    /** 收到一次播放器位置采样。暂停直接对齐；播放中平滑吸收小误差，实际回退或大跨度跳转直接对齐。 */
    fun accept(position: Long, nowNanos: Long, running: Boolean): Long {
        val movedBackward = position.toDouble() < anchorPosition
        anchorPosition = position.toDouble()
        anchorNanos = nowNanos
        val error = position - rendered
        if (!running || !this.running) {
            rendered = position.toDouble()
            lastFrameNanos = nowNanos
        } else if (movedBackward || error > FORWARD_JUMP_MS || error < -SEEK_BACKWARD_MS) {
            rendered = position.toDouble()
            lastFrameNanos = nowNanos
        }
        // 未发生实际回退的中小误差（采样抖动 / 时钟漂移）：rendered 不动，由 frame() 减速收敛。
        this.running = running
        return rendered.toLong()
    }

    /** 明确的歌词点击跳转不受采样抖动阈值限制，即使小幅回退也立即对齐。 */
    fun seekTo(position: Long, nowNanos: Long, running: Boolean): Long {
        anchorPosition = position.toDouble()
        anchorNanos = nowNanos
        rendered = position.toDouble()
        lastFrameNanos = nowNanos
        this.running = running
        return position
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
        if (error > FORWARD_JUMP_MS) {
            rendered = truth
            return rendered.toLong()
        }
        // 向后误差由平滑追赶吸收，不硬跳，避免逐字擦除回弹
        val correction = error * (1.0 - exp(-dtMs / SMOOTH_MS))
        val step = advance + correction
        // error >= 0（落后）：限制最多 1.5 倍速追赶，避免猛冲
        // error < 0（超前）：允许减速到接近 0 让 truth 追平，但不倒退（单调性）
        rendered += if (error >= 0) step.coerceIn(advance * 0.5, advance * 1.5)
                    else step.coerceIn(advance * 0.05, advance)
        return rendered.toLong()
    }

    private companion object {
        /** 误差吸收时间常数：远大于采样抖动周期，远小于人能察觉的不同步。 */
        const val SMOOTH_MS = 250.0
        /** 超前于采样这么多才直接跳（缓冲恢复 / 进度条前跳）。 */
        const val FORWARD_JUMP_MS = 300.0
        /** 相对渲染位置的大幅回退直接对齐；未倒退采样的小误差靠 frame() 减速吸收。 */
        const val SEEK_BACKWARD_MS = 500.0
        /** 单帧最大推进间隔（后台恢复 / 掉帧时不一次冲过头，由误差修正接管）。 */
        const val MAX_FRAME_MS = 100.0
    }
}
