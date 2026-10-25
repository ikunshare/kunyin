package com.ikunshare.music.ui.screens.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class LyricPlaybackClockTest {
    private val frameNanos = 11_111_111L // 90Hz

    /** 200ms 采样、±20ms 时间误差下：逐帧单调、步长平滑、与真实位置误差有界。 */
    @Test
    fun jitteredSamplesProduceSmoothMonotonicTime() {
        val random = Random(42)
        val clock = LyricPlaybackClock(0L, 0L)
        clock.accept(0L, 0L, running = true)
        var now = 0L
        var nextSample = 200_000_000L
        var previous = 0L
        var maxError = 0L
        repeat(90 * 30) {
            now += frameNanos
            if (now >= nextSample) {
                val jitterMs = random.nextLong(-20, 21)
                val truePosition = now / 1_000_000L
                clock.accept(truePosition + jitterMs, now, running = true)
                nextSample += 200_000_000L
            }
            val position = clock.frame(now, 1f)
            val step = position - previous
            // 一帧 11.1ms：采样抖动被平滑吸收，步长只在 ±2ms 内浮动（另留 1ms 取整误差）
            assertTrue("step $step at ${now / 1_000_000}", step in 8..14)
            maxError = maxOf(maxError, abs(position - now / 1_000_000L))
            previous = position
        }
        assertTrue("max error $maxError", maxError <= 30)
    }

    @Test
    fun seekJumpsImmediately() {
        val clock = LyricPlaybackClock(0L, 0L)
        clock.accept(10_000L, 0L, running = true)
        clock.frame(frameNanos, 1f)
        // 向后跳 1 秒（点歌词跳转）
        clock.accept(9_000L, 2 * frameNanos, running = true)
        val position = clock.frame(3 * frameNanos, 1f)
        assertTrue("position $position", position in 9_000L..9_020L)
        // 向前跳 5 秒（拖动进度条）
        clock.accept(14_000L, 4 * frameNanos, running = true)
        assertEquals(14_000L, clock.frame(4 * frameNanos, 1f))
    }

    @Test
    fun pausedClockHoldsSample() {
        val clock = LyricPlaybackClock(0L, 0L)
        clock.accept(5_000L, 0L, running = false)
        assertEquals(5_000L, clock.frame(50 * frameNanos, 1f))
    }

    @Test
    fun followsPlaybackRate() {
        val clock = LyricPlaybackClock(0L, 0L)
        clock.accept(0L, 0L, running = true)
        var now = 0L
        repeat(90) {
            now += frameNanos
            clock.frame(now, 2f)
        }
        val position = clock.frame(now, 2f)
        assertTrue("position $position", abs(position - 2_000L) <= 5)
    }
}
