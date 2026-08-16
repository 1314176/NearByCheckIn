// @Time: 2026/08/02 23:30
// @Author: MrWu
// @File: AccessibilityGestureTapDispatcherTest.kt
// @Software: Android Studio


package com.wu.nearbycheckin.platform.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccessibilityGestureTapDispatcherTest {

    // 验证高德第二次点击由系统手势在第两千毫秒准确启动。
    @Test
    fun preservesRequestedSecondTapTiming() {
        assertEquals(
            2_000L,
            PackageGestureTimingPolicy.secondTapStartMillis(
                requestedDelayMillis = 2_000L,
                tapDurationMillis = 80L
            )
        )
    }

    // 验证单击请求不会被错误扩展为双击序列。
    @Test
    fun keepsSingleTapWithoutSecondDelay() {
        assertEquals(
            0L,
            PackageGestureTimingPolicy.secondTapStartMillis(
                requestedDelayMillis = 0L,
                tapDurationMillis = 80L
            )
        )
    }

    // 验证过短间隔会被限制到首次按压结束之后。
    @Test
    fun preventsOverlappingTapStrokes() {
        assertEquals(
            80L,
            PackageGestureTimingPolicy.secondTapStartMillis(
                requestedDelayMillis = 20L,
                tapDurationMillis = 80L
            )
        )
    }

    // 验证上半屏高德窗口使用顶部中央安全点聚焦。
    @Test
    fun selectsSafeFocusPointForTopSplitWindow() {
        assertEquals(
            GestureTapPoint(540, 59),
            PackageGestureFocusPolicy.select(
                left = 0,
                top = 0,
                right = 1080,
                bottom = 1188
            )
        )
    }

    // 验证下半屏窗口按相对边界计算聚焦点而不是使用固定坐标。
    @Test
    fun selectsRelativeFocusPointForBottomSplitWindow() {
        assertEquals(
            GestureTapPoint(540, 1269),
            PackageGestureFocusPolicy.select(
                left = 0,
                top = 1212,
                right = 1080,
                bottom = 2358
            )
        )
    }

    // 验证无效窗口边界不会生成可能误触其他应用的聚焦点。
    @Test
    fun rejectsInvalidWindowBounds() {
        assertNull(
            PackageGestureFocusPolicy.select(
                left = 0,
                top = 0,
                right = 0,
                bottom = 1188
            )
        )
    }
}
