// @Time: 2026/08/02 23:30
// @Author: MrWu
// @File: AccessibilityGestureTapDispatcherTest.kt
// @Software: Android Studio


package com.wu.nearbycheckin.platform.accessibility


import com.wu.nearbycheckin.core.domain.plugin.PluginCommandType
import com.wu.nearbycheckin.core.model.MapProvider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // 验证百度占用期间排队的高德会在百度下一次执行前获得手势权。
    @Test
    fun alternatesWaitingProvidersWithoutStarvation() {
        val queue = ProviderGestureTurnQueue(staleAfterMillis = 3_000L)

        assertTrue(queue.canAcquire(MapProvider.BAIDU, false, 0L))
        assertFalse(queue.canAcquire(MapProvider.AMAP, true, 100L))
        assertFalse(queue.canAcquire(MapProvider.BAIDU, true, 110L))
        assertFalse(queue.canAcquire(MapProvider.BAIDU, false, 500L))
        assertTrue(queue.canAcquire(MapProvider.AMAP, false, 500L))
        assertTrue(queue.canAcquire(MapProvider.BAIDU, false, 501L))
    }

    // 验证停止重试的 Provider 不会永久阻塞仍在运行的地图。
    @Test
    fun removesStaleProviderWaiter() {
        val queue = ProviderGestureTurnQueue(staleAfterMillis = 3_000L)

        assertFalse(queue.canAcquire(MapProvider.AMAP, true, 0L))
        assertTrue(queue.canAcquire(MapProvider.BAIDU, false, 3_001L))
    }

    // 验证高德页面导航绕过可能假成功的节点点击并优先使用真实手势。
    @Test
    fun prefersGestureForAmapNavigation() {
        assertTrue(
            PluginCommandGesturePolicy.shouldPreferGesture(
                MapProvider.AMAP,
                PluginCommandType.NAVIGATE_FOOTPRINT
            )
        )
        assertTrue(
            PluginCommandGesturePolicy.shouldPreferGesture(
                MapProvider.AMAP,
                PluginCommandType.SELECT_CANDIDATE
            )
        )
        assertFalse(
            PluginCommandGesturePolicy.shouldPreferGesture(
                MapProvider.BAIDU,
                PluginCommandType.NAVIGATE_FOOTPRINT
            )
        )
    }

    // 验证只有带正间隔的高德打卡命令会在首次完成后重新定位第二次点击。
    @Test
    fun usesDeferredRepeatOnlyForAmapCheckInCommands() {
        assertTrue(
            PluginCommandGesturePolicy.shouldUseDeferredRepeat(
                provider = MapProvider.AMAP,
                commandType = PluginCommandType.SELECT_CANDIDATE,
                repeatAfterMillis = 2_000L
            )
        )
        assertTrue(
            PluginCommandGesturePolicy.shouldUseDeferredRepeat(
                provider = MapProvider.AMAP,
                commandType = PluginCommandType.OPEN_CHECK_IN_ENTRY,
                repeatAfterMillis = 2_000L
            )
        )
        assertFalse(
            PluginCommandGesturePolicy.shouldUseDeferredRepeat(
                provider = MapProvider.AMAP,
                commandType = PluginCommandType.NAVIGATE_MINE,
                repeatAfterMillis = 2_000L
            )
        )
        assertFalse(
            PluginCommandGesturePolicy.shouldUseDeferredRepeat(
                provider = MapProvider.BAIDU,
                commandType = PluginCommandType.SELECT_CANDIDATE,
                repeatAfterMillis = 2_000L
            )
        )
        assertFalse(
            PluginCommandGesturePolicy.shouldUseDeferredRepeat(
                provider = MapProvider.AMAP,
                commandType = PluginCommandType.SELECT_CANDIDATE,
                repeatAfterMillis = 0L
            )
        )
    }
}
