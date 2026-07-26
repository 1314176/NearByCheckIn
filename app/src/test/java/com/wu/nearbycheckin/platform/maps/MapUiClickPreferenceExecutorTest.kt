// @Time: 2026/07/26 23:51
// @Author: MrWu
// @File: MapUiClickPreferenceExecutorTest.kt
// @Software: Android Studio


package com.wu.nearbycheckin.platform.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapUiClickPreferenceExecutorTest {

    // 验证手势优先时不调用可能虚假成功的节点点击。
    @Test
    fun usesGestureWithoutCallingNodeWhenPreferred() {
        var nodeCalled = false
        var gestureCalled = false

        val result = MapUiClickPreferenceExecutor.execute(
            preferGesture = true,
            nodeClick = {
                nodeCalled = true
                AccessibilityClickResult(true, "节点点击")
            },
            gestureClick = {
                gestureCalled = true
                AccessibilityClickResult(true, "手势点击")
            }
        )

        assertFalse(nodeCalled)
        assertTrue(gestureCalled)
        assertEquals("手势点击", result.message)
    }

    // 验证节点优先时节点成功不会重复提交手势。
    @Test
    fun keepsSuccessfulNodeClickWhenGestureIsNotPreferred() {
        var gestureCalled = false

        val result = MapUiClickPreferenceExecutor.execute(
            preferGesture = false,
            nodeClick = {
                AccessibilityClickResult(true, "节点点击")
            },
            gestureClick = {
                gestureCalled = true
                AccessibilityClickResult(true, "手势点击")
            }
        )

        assertFalse(gestureCalled)
        assertEquals("节点点击", result.message)
    }

    // 验证节点点击失败后仍能沿用现有手势回退。
    @Test
    fun fallsBackToGestureAfterNodeFailure() {
        val result = MapUiClickPreferenceExecutor.execute(
            preferGesture = false,
            nodeClick = {
                AccessibilityClickResult(false, "节点点击失败")
            },
            gestureClick = {
                AccessibilityClickResult(true, "手势点击")
            }
        )

        assertTrue(result.success)
        assertEquals("手势点击", result.message)
    }
}
