// @Time: 2026/10/26 00:26
// @Author: MrWu
// @File: CheckInRangeRecoveryPolicyTest.kt
// @Software: Android Studio

package com.wu.nearbycheckin.core.domain.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckInRangeRecoveryPolicyTest {

    // 两地图常见超范围文案可以识别，但正常距离说明、额度和导航提醒不可误判。
    @Test
    fun recognizesRejectionWithoutTreatingOrdinaryDistanceAsFailure() {
        listOf("该地点已超过可打卡范围", "当前位置不在打卡范围内", "已超出打卡距离",
            "距离打卡地点太远", "超出签到范围").forEach {
            assertTrue(it, CheckInRangeEvidencePolicy.matches(it))
        }
        listOf("打卡范围500米", "距离打卡点80米", "已超过导航范围", "今日打卡额度已用尽")
            .forEach { assertFalse(it, CheckInRangeEvidencePolicy.matches(it)) }
    }

    // 迟到、重复和其他命令的 Toast 均不能取消当前新任务。
    @Test
    fun consumesOnlyFreshEvidenceForTheSameTaskAndCommand() {
        val policy = CheckInRangeRecoveryPolicy()
        policy.record("task", "command", "已超出打卡范围", 100)
        assertNull(policy.consume("next-task", "command", 101))
        policy.record("task", "command", "已超出打卡范围", 100)
        assertNull(policy.consume("task", "other-command", 101))
        policy.record("task", "command", "已超出打卡范围", 100)
        assertNull(policy.consume("task", "command", 10_101))
        policy.record("task", "command", "已超出打卡范围", 100)
        assertEquals("已超出打卡范围", policy.consume("task", "command", 101))
        assertNull(policy.consume("task", "command", 102))
    }

    // 新定位尚未到达或还停留在旧列表时不得恢复点击，返回入口后才允许重新选点。
    @Test
    fun requiresNewLocationAndReturnToEntryBeforeResuming() {
        val policy = CheckInRangeRecoveryPolicy()
        assertTrue(policy.begin(1_000))
        assertFalse(policy.begin(2_000))
        assertFalse(policy.canResume(PluginPage.CHECK_IN_ENTRY, 1_000, true))
        assertFalse(policy.canResume(PluginPage.CHECK_IN_ENTRY, 1_001, false))
        assertFalse(policy.canResume(PluginPage.CANDIDATE_LIST, 1_001, true))
        assertFalse(policy.canResume(PluginPage.OUT_OF_RANGE, 1_001, true))
        assertTrue(policy.canResume(PluginPage.CHECK_IN_ENTRY, 1_001, true))
        policy.reset()
        assertFalse(policy.isRecovering)
    }
}
