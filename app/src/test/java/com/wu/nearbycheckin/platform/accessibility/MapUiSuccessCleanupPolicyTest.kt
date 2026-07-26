// @Time: 2026/07/26 23:49
// @Author: MrWu
// @File: MapUiSuccessCleanupPolicyTest.kt
// @Software: Android Studio


package com.wu.nearbycheckin.platform.accessibility

import com.wu.nearbycheckin.core.model.MapProvider
import com.wu.nearbycheckin.platform.maps.AccessibilityClickResult
import com.wu.nearbycheckin.platform.maps.MapUiAction
import com.wu.nearbycheckin.platform.maps.MapUiPage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapUiSuccessCleanupPolicyTest {

    // 验证只有百度成功页“完成”动作属于最佳努力清理。
    @Test
    fun selectsOnlyBaiduDoneAction() {
        assertTrue(
            MapUiSuccessCleanupPolicy.shouldExecute(
                provider = MapProvider.BAIDU,
                page = MapUiPage.SUCCESS,
                action = MapUiAction.CLICK_DONE
            )
        )
        assertFalse(
            MapUiSuccessCleanupPolicy.shouldExecute(
                provider = MapProvider.AMAP,
                page = MapUiPage.SUCCESS,
                action = MapUiAction.CLICK_DONE
            )
        )
    }

    // 验证清理失败证据明确记录失败。
    @Test
    fun recordsCleanupFailureEvidence() {
        val evidence = MapUiSuccessCleanupPolicy.appendEvidence(
            profileEvidence = "page=SUCCESS",
            clickResult = AccessibilityClickResult(
                success = false,
                message = "未找到节点：完成"
            )
        )

        assertTrue(evidence.contains("cleanup=failed"))
        assertTrue(evidence.contains("未找到节点：完成"))
    }

    // 验证当前命令已提交打卡时清理失败仍完成原成功结果。
    @Test
    fun finishesConfirmedSuccessWhenCleanupFails() {
        assertEquals(
            MapUiSuccessCleanupDisposition.FINISH_SUCCESS,
            MapUiSuccessCleanupPolicy.dispositionAfter(
                checkInSubmitted = true,
                clickResult = AccessibilityClickResult(
                    success = false,
                    message = "节点点击失败：完成"
                )
            )
        )
    }

    // 验证陈旧成功页清理成功后继续当前新命令。
    @Test
    fun continuesNewCommandAfterStalePageCleanup() {
        assertEquals(
            MapUiSuccessCleanupDisposition.CONTINUE_COMMAND,
            MapUiSuccessCleanupPolicy.dispositionAfter(
                checkInSubmitted = false,
                clickResult = AccessibilityClickResult(
                    success = true,
                    message = "已点击节点：完成"
                )
            )
        )
    }

    // 验证陈旧成功页清理失败时不伪造新命令成功。
    @Test
    fun failsNewCommandWhenStalePageCannotBeCleaned() {
        assertEquals(
            MapUiSuccessCleanupDisposition.FAIL_COMMAND,
            MapUiSuccessCleanupPolicy.dispositionAfter(
                checkInSubmitted = false,
                clickResult = AccessibilityClickResult(
                    success = false,
                    message = "节点点击失败：完成"
                )
            )
        )
    }
}
