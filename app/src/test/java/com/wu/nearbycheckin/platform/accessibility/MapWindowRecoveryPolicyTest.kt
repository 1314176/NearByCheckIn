// @Time: 2026/07/26 00:00
// @Author: MrWu
// @File: MapWindowRecoveryPolicyTest.kt
// @Software: Android Studio


package com.wu.nearbycheckin.platform.accessibility


import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test


/**
 * 验证后台地图窗口恢复请求的节流与等待退避。
 */
class MapWindowRecoveryPolicyTest {

    private val policy = MapWindowRecoveryPolicy(
        statusIntervalAttempts = 5,
        fastWaitMaxAttempts = 30,
        fastWaitDelayMillis = 1_000L,
        idleWaitDelayMillis = 5_000L
    )

    // 验证首次发现窗口转入后台时立即提示任务已保留。
    @Test
    fun reportsWaitingImmediately() {
        assertTrue(policy.shouldReportWaiting(0))
    }

    // 验证连续等待期间不会每秒重复记录后台提示。
    @Test
    fun throttlesRepeatedWaitingReports() {
        assertFalse(policy.shouldReportWaiting(1))
        assertFalse(policy.shouldReportWaiting(4))
        assertTrue(policy.shouldReportWaiting(5))
    }

    // 验证短时间切换后台时保持快速恢复检查。
    @Test
    fun usesFastWaitDelayInitially() {
        assertEquals(1_000L, policy.nextWaitDelayMillis(29))
    }

    // 验证长时间停留后台时降低恢复检查频率但不结束任务。
    @Test
    fun usesIdleWaitDelayWithoutTimingOutTask() {
        assertEquals(5_000L, policy.nextWaitDelayMillis(30))
        assertTrue(policy.shouldReportWaiting(35))
    }
}
