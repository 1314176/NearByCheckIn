// @Time: 2026/07/26 01:29
// @Author: MrWu
// @File: DualMapPaneLaunchPolicyTest.kt
// @Software: Android Studio


package com.wu.nearbycheckin.platform.multiwindow


import android.content.Intent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test


class DualMapPaneLaunchPolicyTest {

    // 验证百度接管当前窗格时使用独立任务并优先复用已有任务。
    @Test
    fun `百度接管当前窗格时保留独立任务与任务重排标记`() {
        val flags =
            DualMapPaneLaunchPolicy.currentPaneReplacementFlags(0)

        assertTrue(
            flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0
        )
        assertTrue(
            flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT != 0
        )
    }

    // 验证百度接管当前窗格时不会再次创建相邻窗格或重复任务。
    @Test
    fun `百度接管当前窗格时移除相邻窗格与多任务标记`() {
        val flags =
            DualMapPaneLaunchPolicy.currentPaneReplacementFlags(
                Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            )

        assertEquals(
            0,
            flags and Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
        )
        assertEquals(
            0,
            flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK
        )
    }
}
