// @Time: 2026/07/26 20:29
// @Author: MrWu
// @File: MapUiGestureTargetPolicyTest.kt
// @Software: Android Studio


package com.wu.nearbycheckin.platform.maps

import org.junit.Assert.assertEquals
import org.junit.Test

class MapUiGestureTargetPolicyTest {

    private val window = MapUiBounds(0, 100, 1080, 2200)

    // 验证唯一可见且有效的候选节点生成中心点。
    @Test
    fun selectsCenterOfSingleSafeCandidate() {
        val result = MapUiGestureTargetPolicy.select(
            candidates = listOf(
                MapUiGestureCandidate(
                    enabled = true,
                    visible = true,
                    bounds = MapUiBounds(900, 2000, 1080, 2200)
                )
            ),
            windowBounds = window
        )

        assertEquals(MapUiGestureTarget(990, 2100), result.target)
        assertEquals("", result.rejectionReason)
    }

    // 验证零个或多个匹配节点均关闭回退。
    @Test
    fun rejectsNonUniqueCandidates() {
        assertEquals(
            "匹配节点数量不是一个",
            MapUiGestureTargetPolicy.select(emptyList(), window)
                .rejectionReason
        )
        val candidate = MapUiGestureCandidate(
            enabled = true,
            visible = true,
            bounds = MapUiBounds(900, 2000, 1080, 2200)
        )
        assertEquals(
            "匹配节点数量不是一个",
            MapUiGestureTargetPolicy.select(
                listOf(candidate, candidate),
                window
            ).rejectionReason
        )
    }

    // 验证隐藏重复节点不会阻断唯一可见安全目标。
    @Test
    fun ignoresHiddenDuplicateWhenSelectingSafeTarget() {
        val result = MapUiGestureTargetPolicy.select(
            candidates = listOf(
                MapUiGestureCandidate(
                    enabled = true,
                    visible = false,
                    bounds = MapUiBounds(400, 400, 600, 500)
                ),
                MapUiGestureCandidate(
                    enabled = true,
                    visible = true,
                    bounds = MapUiBounds(400, 500, 600, 600)
                )
            ),
            windowBounds = window
        )

        assertEquals(MapUiGestureTarget(500, 550), result.target)
        assertEquals("", result.rejectionReason)
    }

    // 验证禁用、不可见、空边界和中心越界均关闭回退。
    @Test
    fun rejectsUnsafeCandidateStateAndBounds() {
        // 返回单个候选节点的拒绝原因。
        fun rejection(
            candidate: MapUiGestureCandidate,
            windowBounds: MapUiBounds = window
        ) = MapUiGestureTargetPolicy.select(
            listOf(candidate),
            windowBounds
        ).rejectionReason

        assertEquals(
            "节点未启用",
            rejection(
                MapUiGestureCandidate(
                    false,
                    true,
                    MapUiBounds(900, 2000, 1080, 2200)
                )
            )
        )
        assertEquals(
            "节点对用户不可见",
            rejection(
                MapUiGestureCandidate(
                    true,
                    false,
                    MapUiBounds(900, 2000, 1080, 2200)
                )
            )
        )
        assertEquals(
            "节点边界无效",
            rejection(
                MapUiGestureCandidate(
                    true,
                    true,
                    MapUiBounds(900, 2000, 900, 2200)
                )
            )
        )
        assertEquals(
            "节点中心不在目标窗口内",
            rejection(
                MapUiGestureCandidate(
                    true,
                    true,
                    MapUiBounds(1080, 2000, 1280, 2200)
                )
            )
        )
    }
}
