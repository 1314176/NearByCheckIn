// @Time: 2026/07/27 00:46
// @Author: MrWu
// @File: AmapCheckInButtonGesturePolicyTest.kt
// @Software: Android Studio

package com.wu.nearbycheckin.platform.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmapCheckInButtonGesturePolicyTest {

    private val window = MapUiBounds(0, 0, 1080, 1188)

    // 验证从真机右下角按钮边界生成中心点击位置。
    @Test
    fun selectsCheckInButtonCenter() {
        val result = AmapCheckInButtonGesturePolicy.select(
            candidates = listOf(
                candidate(MapUiBounds(0, 0, 1080, 1188)),
                candidate(MapUiBounds(52, 957, 1028, 1093)),
                candidate(MapUiBounds(810, 958, 1028, 1083)),
                candidate(MapUiBounds(810, 958, 1028, 1083))
            ),
            windowBounds = window
        )

        assertEquals(MapUiGestureTarget(919, 1020), result.target)
        assertEquals("", result.rejectionReason)
    }

    // 验证按钮未独立暴露时从底部操作栏计算打卡位置。
    @Test
    fun selectsCheckInButtonFromWideActionBar() {
        val result = AmapCheckInButtonGesturePolicy.select(
            candidates = listOf(
                candidate(MapUiBounds(0, 0, 1080, 1188)),
                candidate(MapUiBounds(52, 957, 1028, 1093))
            ),
            windowBounds = window
        )

        assertEquals(MapUiGestureTarget(920, 1025), result.target)
        assertEquals("", result.rejectionReason)
    }

    // 验证不存在右下角按钮边界时拒绝生成目标。
    @Test
    fun rejectsPageWithoutCheckInButton() {
        val result = AmapCheckInButtonGesturePolicy.select(
            candidates = listOf(
                candidate(MapUiBounds(0, 0, 1080, 1188)),
                candidate(MapUiBounds(21, 444, 1059, 759))
            ),
            windowBounds = window
        )

        assertNull(result.target)
        assertEquals("未找到高德打卡按钮锚点", result.rejectionReason)
    }

    // 验证“我的”页底部导航不会被误识别为打卡按钮。
    @Test
    fun rejectsBottomNavigationOnMinePage() {
        val result = AmapCheckInButtonGesturePolicy.select(
            candidates = listOf(
                candidate(MapUiBounds(0, 0, 1080, 1188)),
                candidate(MapUiBounds(830, 1038, 1080, 1188))
            ),
            windowBounds = window
        )

        assertNull(result.target)
        assertEquals("未找到高德打卡按钮锚点", result.rejectionReason)
    }

    // 构造启用且可见的高德按钮候选。
    private fun candidate(bounds: MapUiBounds) =
        MapUiGestureCandidate(
            enabled = true,
            visible = true,
            bounds = bounds
        )
}
