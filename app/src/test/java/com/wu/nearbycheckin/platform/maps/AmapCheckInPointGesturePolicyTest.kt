// @Time: 2026/07/27 00:38
// @Author: MrWu
// @File: AmapCheckInPointGesturePolicyTest.kt
// @Software: Android Studio

package com.wu.nearbycheckin.platform.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmapCheckInPointGesturePolicyTest {

    private val window = MapUiBounds(0, 0, 1080, 1188)

    // 验证从真机足迹卡片边界计算“打卡点”的相对点击位置。
    @Test
    fun selectsRelativeCheckInPointFromFootprintCard() {
        val result = AmapCheckInPointGesturePolicy.select(
            candidates = listOf(
                candidate(MapUiBounds(0, 0, 1080, 1188)),
                candidate(MapUiBounds(0, 444, 1080, 780)),
                candidate(MapUiBounds(21, 444, 1059, 780)),
                candidate(MapUiBounds(21, 444, 1059, 759)),
                candidate(MapUiBounds(21, 444, 1059, 759))
            ),
            windowBounds = window
        )

        assertEquals(MapUiGestureTarget(529, 645), result.target)
        assertEquals("", result.rejectionReason)
    }

    // 验证缺少稳定足迹卡片边界时拒绝生成点击位置。
    @Test
    fun rejectsPageWithoutFootprintCardAnchor() {
        val result = AmapCheckInPointGesturePolicy.select(
            candidates = listOf(
                candidate(MapUiBounds(0, 0, 1080, 1188)),
                candidate(MapUiBounds(0, 220, 1080, 443))
            ),
            windowBounds = window
        )

        assertNull(result.target)
        assertEquals("未找到高德足迹卡片锚点", result.rejectionReason)
    }

    // 构造启用且可见的高德卡片候选。
    private fun candidate(bounds: MapUiBounds) =
        MapUiGestureCandidate(
            enabled = true,
            visible = true,
            bounds = bounds
        )
}
