package com.wu.nearbycheckin.platform.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapUiGestureFallbackExecutorTest {

    // 验证已接受目标只提交一次手势。
    @Test
    fun submitsAcceptedTargetOnce() {
        var callCount = 0
        val result = MapUiGestureFallbackExecutor.execute(
            selection = MapUiGestureTargetSelection(
                target = MapUiGestureTarget(990, 2100)
            ),
            tap = { _, _ ->
                callCount += 1
                true
            }
        )

        assertTrue(result.success)
        assertEquals(1, callCount)
        assertEquals("语义边界手势已提交", result.message)
    }

    // 验证拒绝目标不调用手势。
    @Test
    fun doesNotTapRejectedTarget() {
        var called = false
        val result = MapUiGestureFallbackExecutor.execute(
            selection = MapUiGestureTargetSelection(
                rejectionReason = "匹配节点数量不是一个"
            ),
            tap = { _, _ ->
                called = true
                true
            }
        )

        assertFalse(result.success)
        assertFalse(called)
        assertEquals(
            "语义边界手势已拒绝：匹配节点数量不是一个",
            result.message
        )
    }

    // 验证平台拒绝手势提交时返回失败。
    @Test
    fun reportsPlatformSubmissionFailure() {
        val result = MapUiGestureFallbackExecutor.execute(
            selection = MapUiGestureTargetSelection(
                target = MapUiGestureTarget(990, 2100)
            ),
            tap = { _, _ -> false }
        )

        assertFalse(result.success)
        assertEquals("语义边界手势提交失败", result.message)
    }
}
