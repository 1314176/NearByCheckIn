// @Time: 2026/08/02 23:30
// @Author: MrWu
// @File: AccessibilityGestureTapDispatcherTest.kt
// @Software: Android Studio


package com.wu.nearbycheckin.platform.accessibility


import com.wu.nearbycheckin.core.domain.plugin.PluginCommandTarget
import com.wu.nearbycheckin.core.domain.plugin.PluginCommandType
import com.wu.nearbycheckin.core.model.MapProvider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityGestureTapDispatcherTest {

    // 验证同名模板入口不能覆盖已经绑定的真实打卡页签。
    @Test
    fun selectsPinnedTargetInsteadOfEarlierDuplicateLabel() {
        assertEquals("tab", PluginCommandTargetPolicy.select(
            candidates = listOf("camera", "tab"),
            target = PluginCommandTarget(labels = listOf("打卡"), nodeFingerprint = "tab"),
            fingerprint = { it }, viewId = { "" }, nodeLabels = { listOf("打卡") }
        ))
    }

    // 验证固定节点失效后不按文案或视图标识改点其他入口。
    @Test
    fun rejectsStalePinnedTargetWithoutLabelOrViewIdFallback() {
        assertNull(PluginCommandTargetPolicy.select(
            candidates = listOf("camera"),
            target = PluginCommandTarget(labels = listOf("打卡"),
                viewId = "same-id", nodeFingerprint = "old-tab"),
            fingerprint = { it }, viewId = { "same-id" }, nodeLabels = { listOf("打卡") }
        ))
    }

    // 验证未绑定指纹的旧命令仍可按稳定视图标识和标签优先级定位。
    @Test
    fun keepsViewIdAndLabelSelectionForUnpinnedCommands() {
        assertEquals("back", PluginCommandTargetPolicy.select(
            candidates = listOf("other", "back"),
            target = PluginCommandTarget(viewId = "back"),
            fingerprint = { it }, viewId = { it }, nodeLabels = { emptyList() }
        ))
        assertEquals("收下", PluginCommandTargetPolicy.select(
            candidates = listOf("关闭", "收下"),
            target = PluginCommandTarget(labels = listOf("收下", "关闭")),
            fingerprint = { it }, viewId = { "" }, nodeLabels = { listOf(it) }
        ))
    }

    // 没有候选的地图撤回等待权后，另一地图不必等到票据过期。
    @Test
    fun emptyProviderReleasesItsTurnImmediately() {
        val queue = ProviderGestureTurnQueue()
        assertFalse(queue.canAcquire(MapProvider.AMAP, true, 0))
        assertFalse(queue.canAcquire(MapProvider.BAIDU, true, 1))
        queue.cancel(MapProvider.AMAP)
        assertTrue(queue.canAcquire(MapProvider.BAIDU, false, 100))
    }

    // 超时释放后迟到回调不能释放正在执行的新手势，也不能重复完成旧请求。
    @Test
    fun lateCallbackCannotReleaseNewGesture() {
        val lifecycle = GestureDispatchLifecycle()
        val timedOut = lifecycle.begin()
        assertTrue(lifecycle.finish(timedOut))
        val next = lifecycle.begin()
        assertFalse(lifecycle.finish(timedOut))
        assertTrue(lifecycle.finish(next))
        assertFalse(lifecycle.finish(next))
    }

    // 服务重连后旧实例的手势回调必须失效。
    @Test
    fun serviceStopInvalidatesGestureCallbacks() {
        val lifecycle = GestureDispatchLifecycle()
        val old = lifecycle.begin()
        lifecycle.invalidate()
        val next = lifecycle.begin()
        assertFalse(lifecycle.finish(old))
        assertTrue(lifecycle.finish(next))
    }

    // 验证执行前的预检不会消耗高德轮次而导致实际提交被百度等待者挡住。
    @Test
    fun preservesTurnUntilActualCommandSubmission() {
        val queue = ProviderGestureTurnQueue()
        assertFalse(queue.canAcquire(MapProvider.AMAP, true, 0L))
        assertFalse(queue.canAcquire(MapProvider.BAIDU, true, 1L))
        assertTrue(queue.canAcquire(MapProvider.AMAP, false, 500L, consume = false))
        assertTrue(queue.canAcquire(MapProvider.AMAP, false, 501L))
        assertTrue(queue.canAcquire(MapProvider.BAIDU, false, 502L))
    }

    // 验证“收下”优先于树中更早出现的“完成”和“关闭”，且不选择内部占位名称。
    @Test
    fun selectsDismissButtonInDeclaredPriority() {
        assertEquals("收下", PluginCommandLabelPolicy.select(
            listOf("关闭", "完成", "收下"), listOf("收下", "完成", "关闭")
        ) { listOf(it) })
        assertEquals("收下,按钮", PluginCommandLabelPolicy.select(
            listOf("完成", "收下,按钮"), listOf("收下", "完成")
        ) { listOf(it) })
        assertEquals("开始打卡", PluginCommandLabelPolicy.select(
            listOf("打卡", "开始打卡"), listOf("开始打卡", "打卡")
        ) { listOf(it) })
        assertNull(PluginCommandLabelPolicy.select(
            listOf("当前高德打卡位置"), listOf("开始打卡", "打卡")
        ) { listOf(it) })
    }

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

    // 验证上半屏不再生成可能唤起系统分屏操作条的顶部聚焦点击。
    @Test
    fun selectsSafeFocusPointForTopSplitWindow() {
        assertNull(
            PackageGestureFocusPolicy.select(
                left = 0,
                top = 0,
                right = 1080,
                bottom = 1188
            )
        )
    }

    // 验证下半屏同样不生成分屏顶栏聚焦点击。
    @Test
    fun selectsRelativeFocusPointForBottomSplitWindow() {
        assertNull(
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

    // 验证分屏边缘只露出部分的高德“收下”按钮点击可见交集中心。
    @Test
    fun clipsAmapDismissTargetToVisibleWindow() {
        assertEquals(
            GestureTapPoint(540, 1164),
            PluginCommandTargetBoundsPolicy.selectVisiblePoint(
                targetLeft = 252,
                targetTop = 1140,
                targetRight = 828,
                targetBottom = 1260,
                windowLeft = 0,
                windowTop = 0,
                windowRight = 1080,
                windowBottom = 1188
            )
        )
    }

    // 验证横向容器中已移出屏幕的旧成功卡片不会再被点击。
    @Test
    fun rejectsOffscreenAmapResultTarget() {
        assertNull(
            PluginCommandTargetBoundsPolicy.selectVisiblePoint(
                targetLeft = -12_463,
                targetTop = 1_243,
                targetRight = 0,
                targetBottom = 1_873,
                windowLeft = 0,
                windowTop = 1_212,
                windowRight = 1080,
                windowBottom = 2_400
            )
        )
    }

    // 验证百度跳转期间高德不能插入点击，期限结束后立即恢复公平调度。
    @Test
    fun isolatesNavigationUntilPageSettlesOrLeaseExpires() {
        val lease = ProviderNavigationLease()
        lease.reserve(MapProvider.BAIDU, 1_500L)
        assertTrue(lease.isBlocked(MapProvider.AMAP, 1_000L))
        assertFalse(lease.isBlocked(MapProvider.BAIDU, 1_000L))
        assertFalse(lease.isBlocked(MapProvider.AMAP, 1_500L))
        lease.release(MapProvider.BAIDU)
        assertFalse(lease.isBlocked(MapProvider.AMAP, 1_001L))
    }

    // 验证百度迟到的释放不能清掉高德的新跳转执行权。
    @Test
    fun staleProviderCannotReleaseAnotherNavigation() {
        val lease = ProviderNavigationLease()
        lease.reserve(MapProvider.AMAP, 2_000L)
        lease.release(MapProvider.BAIDU)
        assertTrue(lease.isBlocked(MapProvider.BAIDU, 1_000L))
        lease.clear()
        assertFalse(lease.isBlocked(MapProvider.BAIDU, 1_000L))
    }

    // 验证连续无效跳转会退避并让出点击权，恢复后不遗留失败计数。
    @Test
    fun repeatedNavigationFailureBacksOffInsteadOfClickingForever() {
        val policy = NavigationRetryPolicy()
        policy.recordTimeout(1_000L)
        policy.recordTimeout(2_000L)
        assertTrue(policy.canRetry(2_000L))
        policy.recordTimeout(3_000L)
        assertFalse(policy.canRetry(12_999L))
        assertTrue(policy.canRetry(13_000L))
        policy.reset()
        policy.recordTimeout(14_000L)
        assertTrue(policy.canRetry(14_000L))
    }
}
