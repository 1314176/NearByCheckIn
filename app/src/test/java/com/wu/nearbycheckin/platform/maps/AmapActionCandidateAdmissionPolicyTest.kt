// @Time: 2026/07/27 01:29
// @Author: MrWu
// @File: AmapActionCandidateAdmissionPolicyTest.kt
// @Software: Android Studio

package com.wu.nearbycheckin.platform.maps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmapActionCandidateAdmissionPolicyTest {

    private val window = MapUiBounds(0, 0, 1080, 1188)

    // 验证高德时间轴生成的离屏节点不会占用动作候选名额。
    @Test
    fun rejectsOffscreenTimelineCandidate() {
        val accepted = AmapActionCandidateAdmissionPolicy.accepts(
            candidate = candidate(
                bounds = MapUiBounds(189, 292, -2999, 355)
            ),
            windowBounds = window
        )

        assertFalse(accepted)
    }

    // 验证高德底部打卡操作栏仍可进入动作候选列表。
    @Test
    fun acceptsVisibleCheckInActionBar() {
        val accepted = AmapActionCandidateAdmissionPolicy.accepts(
            candidate = candidate(
                bounds = MapUiBounds(52, 957, 1028, 1093)
            ),
            windowBounds = window
        )

        assertTrue(accepted)
    }

    // 构造启用且对用户可见的高德动作候选。
    private fun candidate(bounds: MapUiBounds) =
        MapUiGestureCandidate(
            enabled = true,
            visible = true,
            bounds = bounds
        )
}
