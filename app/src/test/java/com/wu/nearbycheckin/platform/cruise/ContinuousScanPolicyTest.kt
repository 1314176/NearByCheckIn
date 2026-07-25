// @Time: 2026/07/25 23:55
// @Author: MrWu
// @File: ContinuousScanPolicyTest.kt
// @Software: Codex

package com.wu.nearbycheckin.platform.cruise

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 验证静止连续扫描在活跃、耗尽、移动和高速状态间切换。
 */
class ContinuousScanPolicyTest {

    private val policy = ContinuousScanPolicy(
        activeIntervalMillis = 15_000L,
        idleIntervalMillis = 60_000L,
        fastIntervalMillis = 1_000L,
        fastSpeedThresholdKmh = 60F
    )

    // 验证初始状态采用正常连续扫描间隔。
    @Test
    fun usesActiveIntervalInitially() {
        assertEquals(15_000L, policy.nextIntervalMillis(0F))
    }

    // 验证当前范围没有新候选后降低扫描频率。
    @Test
    fun usesIdleIntervalWhenCurrentRangeIsExhausted() {
        policy.recordCandidateOutcome(
            acceptedCount = 0,
            deferredCount = 0
        )

        assertEquals(60_000L, policy.nextIntervalMillis(0F))
    }

    // 验证等待停留确认的候选保持正常扫描频率。
    @Test
    fun keepsActiveIntervalForDeferredCandidates() {
        policy.recordCandidateOutcome(
            acceptedCount = 0,
            deferredCount = 2
        )

        assertEquals(15_000L, policy.nextIntervalMillis(0F))
    }

    // 验证位置变化后退出低频巡检状态。
    @Test
    fun resetsIdleIntervalAfterLocationChange() {
        policy.recordCandidateOutcome(
            acceptedCount = 0,
            deferredCount = 0
        )

        policy.resetForLocationChange()

        assertEquals(15_000L, policy.nextIntervalMillis(0F))
    }

    // 验证高速移动优先采用快速扫描间隔。
    @Test
    fun usesFastIntervalWhileMovingQuickly() {
        policy.recordCandidateOutcome(
            acceptedCount = 0,
            deferredCount = 0
        )

        assertEquals(1_000L, policy.nextIntervalMillis(61F))
    }
}
