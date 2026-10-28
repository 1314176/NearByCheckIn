// @Time: 2026/07/26 23:09
// @Author: MrWu
// @File: AccessibilityGestureTapDispatcher.kt
// @Software: Android Studio


package com.wu.nearbycheckin.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityWindowInfo

import com.wu.nearbycheckin.core.model.MapProvider

class AccessibilityGestureTapDispatcher(
    private val service: AccessibilityService
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingTaps = ArrayDeque<TapRequest>()
    private val providerTurnQueue = ProviderGestureTurnQueue()
    private val navigationLease = ProviderNavigationLease()
    private var isDispatching = false
    private var commandReservedUntilMillis = 0L
    private val dispatchLifecycle = GestureDispatchLifecycle()
    private var activeTap: TapRequest? = null

    // 没有可执行任务或 Worker 停止时撤销等待权，避免空队列阻塞另一地图。
    fun cancelWaiting(provider: MapProvider) {
        providerTurnQueue.cancel(provider)
    }

    // 判断共享命令闸门当前是否允许领取新的 UI 任务。
    fun canExecuteCommand(provider: MapProvider): Boolean {
        val nowMillis = SystemClock.elapsedRealtime()
        return providerTurnQueue.canAcquire(
            provider = provider,
            busy = isDispatching || nowMillis < commandReservedUntilMillis ||
                    navigationLease.isBlocked(provider, nowMillis),
            nowMillis = nowMillis,
            consume = false
        )
    }

    // 为节点点击和手势统一申请短时全局命令执行权。
    fun <T> executeAtomically(
        provider: MapProvider,
        block: () -> T
    ): T? {
        val nowMillis = SystemClock.elapsedRealtime()
        if (
            !providerTurnQueue.canAcquire(
                provider = provider,
                busy = isDispatching ||
                        nowMillis < commandReservedUntilMillis ||
                        navigationLease.isBlocked(provider, nowMillis),
                nowMillis = nowMillis
            )
        ) {
            return null
        }
        commandReservedUntilMillis = nowMillis + COMMAND_SETTLE_MILLIS
        return block()
    }

    // 页面跳转确认期间保留当前地图执行权，避免另一地图插入点击。
    fun reserveNavigation(provider: MapProvider, durationMillis: Long) {
        navigationLease.reserve(
            provider,
            SystemClock.elapsedRealtime() + durationMillis
        )
    }

    // 页面已确认变化或等待结束时只释放所属地图的跳转执行权。
    fun releaseNavigation(provider: MapProvider) {
        navigationLease.release(provider)
    }

    // 仅在目标地图持有当前焦点时返回，禁止把另一地图的页面意外关闭。
    fun navigateBackForPackage(packageName: String): Boolean {
        val focusedPackage = service.windows.orEmpty().firstOrNull { it.isFocused }
            ?.root?.packageName?.toString()
        if (!PackageNavigationSafetyPolicy.canNavigateBack(packageName, focusedPackage)) return false
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    // 将短时点击加入共享队列并确保同一时刻只派发一个手势。
    fun tap(x: Int, y: Int): Boolean {
        return enqueueTap(
            TapRequest(
                x = x,
                y = y,
                durationMillis = STANDARD_TAP_DURATION_MILLIS
            )
        )
    }

    // 为目标应用串行提交目标点击，不额外触碰窗口顶部的系统控件。
    fun tapForPackage(
        x: Int,
        y: Int,
        packageName: String,
        onCompleted: ((Boolean) -> Unit)? = null,
        windowId: Int? = null
    ): Boolean {
        return enqueuePackageTap(
            x = x,
            y = y,
            packageName = packageName,
            onCompleted = onCompleted,
            windowId = windowId
        )
    }

    // 上拉面板复用共享手势队列，整段路径必须位于同一地图且避开系统热区。
    internal fun expandPanelForPackage(packageName: String, windowId: Int, anchor: GestureTapBounds): GestureSwipePath? {
        val context = packageTouchContext(packageName, windowId) ?: return null
        val swipe = BaiduPanelSwipePolicy.select(anchor, context.window, context.blockers) ?: return null
        return swipe.takeIf {
            enqueueTap(TapRequest(x = swipe.start.x, y = swipe.start.y,
                endPoint = swipe.end, durationMillis = 550L, packageName = packageName, windowId = windowId))
        }
    }

    // 为目标应用原子提交间隔固定的两次点击，避免主线程阻塞破坏时序。
    fun tapTwiceForPackage(
        x: Int,
        y: Int,
        packageName: String,
        secondTapDelayMillis: Long,
        onCompleted: ((Boolean) -> Unit)? = null
    ): Boolean {
        return enqueuePackageTap(
            x = x,
            y = y,
            packageName = packageName,
            secondTapDelayMillis = secondTapDelayMillis,
            onCompleted = onCompleted
        )
    }

    // 校验目标窗口边界并提交不可插队的应用手势请求。
    private fun enqueuePackageTap(
        x: Int,
        y: Int,
        packageName: String,
        secondTapDelayMillis: Long = 0L,
        onCompleted: ((Boolean) -> Unit)? = null,
        windowId: Int? = null
    ): Boolean {
        val context = packageTouchContext(packageName, windowId) ?: return false
        if (!PackageGestureTargetPolicy.isSafePoint(GestureTapPoint(x, y), context.window, context.blockers)) {
            return false
        }
        val bounds = context.window
        val focusPoint = bounds.let { bounds ->
            PackageGestureFocusPolicy.select(
                left = bounds.left,
                top = bounds.top,
                right = bounds.right,
                bottom = bounds.bottom
            )
        }
        return enqueueTap(
            TapRequest(
                x = x,
                y = y,
                durationMillis = PACKAGE_TAP_DURATION_MILLIS,
                packageName = packageName,
                windowId = windowId,
                focusPoint = focusPoint,
                secondTapStartMillis =
                    PackageGestureTimingPolicy.secondTapStartMillis(
                        requestedDelayMillis = secondTapDelayMillis,
                        tapDurationMillis = PACKAGE_TAP_DURATION_MILLIS
                    ),
                onCompleted = onCompleted
            )
        )
    }

    // 追加一个点击请求并在队列空闲时立即开始派发。
    private fun enqueueTap(request: TapRequest): Boolean {
        pendingTaps.addLast(request)
        if (isDispatching) {
            return true
        }
        return dispatchNext()
    }

    // 清空队列并使当前回调失效，避免服务重连后旧手势释放新实例的执行权。
    fun clear() {
        mainHandler.removeCallbacksAndMessages(null)
        dispatchLifecycle.invalidate()
        val cancelled = activeTap
        activeTap = null
        isDispatching = false
        commandReservedUntilMillis = 0L
        pendingTaps.clear()
        providerTurnQueue.clear()
        navigationLease.clear()
        cancelled?.onCompleted?.invoke(false)
    }

    // 派发队首点击并在系统回调后继续处理下一条。
    private fun dispatchNext(): Boolean {
        if (pendingTaps.isEmpty()) {
            isDispatching = false
            return false
        }
        val tap = pendingTaps.removeFirst()
        val context = if (tap.packageName.isNotBlank()) packageTouchContext(tap.packageName, tap.windowId) else null
        if (tap.packageName.isNotBlank() && (context == null ||
            !PackageGestureTargetPolicy.isSafeGesture(GestureTapPoint(tap.x, tap.y), tap.endPoint,
                context.window, context.blockers))) {
            tap.onCompleted?.invoke(false)
            isDispatching = false
            dispatchNext()
            return false
        }
        val isFocusPrimer = !tap.focusPrimed && tap.focusPoint != null
        if (isFocusPrimer) {
            pendingTaps.addFirst(tap.copy(focusPrimed = true))
        }
        val dispatchPoint = if (isFocusPrimer) {
            requireNotNull(tap.focusPoint)
        } else {
            GestureTapPoint(tap.x, tap.y)
        }
        val path = Path().apply {
            moveTo(
                dispatchPoint.x.toFloat(),
                dispatchPoint.y.toFloat()
            )
            if (!isFocusPrimer) tap.endPoint?.let { lineTo(it.x.toFloat(), it.y.toFloat()) }
        }
        val gestureBuilder = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    tap.durationMillis
                )
            )
        if (!isFocusPrimer && tap.secondTapStartMillis > 0L) {
            gestureBuilder.addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    tap.secondTapStartMillis,
                    tap.durationMillis
                )
            )
        }
        val gesture = gestureBuilder.build()
        isDispatching = true
        activeTap = tap
        val token = dispatchLifecycle.begin()
        val watchdog = Runnable {
            if (dispatchLifecycle.finish(token)) {
                if (isFocusPrimer) pendingTaps.removeFirstOrNull()
                activeTap = null
                isDispatching = false
                tap.onCompleted?.invoke(false)
                dispatchNext()
            }
        }
        mainHandler.postDelayed(watchdog,
            tap.secondTapStartMillis + tap.durationMillis + GESTURE_CALLBACK_TIMEOUT_MILLIS)
        val accepted = service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {

                // 在手势完成后释放仲裁器并派发下一条。
                override fun onCompleted(
                    gestureDescription: GestureDescription?
                ) {
                    if (!dispatchLifecycle.finish(token)) return
                    mainHandler.removeCallbacks(watchdog)
                    activeTap = null
                    if (!isFocusPrimer) {
                        tap.onCompleted?.invoke(true)
                    }
                    continueDispatchAfterGesture(isFocusPrimer)
                }

                // 在手势取消后释放仲裁器并继续后续队列。
                override fun onCancelled(
                    gestureDescription: GestureDescription?
                ) {
                    if (!dispatchLifecycle.finish(token)) return
                    mainHandler.removeCallbacks(watchdog)
                    if (isFocusPrimer) pendingTaps.removeFirstOrNull()
                    activeTap = null
                    tap.onCompleted?.invoke(false)
                    isDispatching = false
                    dispatchNext()
                }
            },
            null
        )
        if (!accepted) {
            if (dispatchLifecycle.finish(token)) {
                mainHandler.removeCallbacks(watchdog)
                if (isFocusPrimer) pendingTaps.removeFirstOrNull()
                activeTap = null
                tap.onCompleted?.invoke(false)
                isDispatching = false
                dispatchNext()
            }
        }
        return accepted
    }

    // 聚焦手势完成后保留仲裁权，等待系统稳定焦点再派发真实点击。
    private fun continueDispatchAfterGesture(
        waitForFocusSettlement: Boolean
    ) {
        if (!waitForFocusSettlement) {
            isDispatching = false
            dispatchNext()
            return
        }
        mainHandler.postDelayed(
            {
                isDispatching = false
                dispatchNext()
            },
            PACKAGE_FOCUS_SETTLE_DELAY_MILLIS
        )
    }

    // 用目标真实窗口减去更高层系统触摸热区，不能只判断点击仍在地图矩形范围内。
    internal fun selectSafeTapPoint(packageName: String, windowId: Int, target: GestureTapBounds): GestureTapPoint? {
        val context = packageTouchContext(packageName, windowId) ?: return null
        return PackageGestureTargetPolicy.select(target, context.window, context.blockers)
    }

    // 系统窗口边界就是可触摸区域的包围矩形，包含伸入地图内容的分隔线热区。
    private fun packageTouchContext(packageName: String, windowId: Int?): PackageTouchContext? {
        val windows = service.windows.orEmpty()
        val target = windows.firstOrNull { window ->
            (windowId == null || window.id == windowId) && window.root?.packageName?.toString() == packageName
        } ?: return null
        val bounds = findPackageWindowBounds(packageName, target.id) ?: return null
        val blockers = windows.filter { window ->
            window.layer > target.layer && window.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
        }.map { window ->
            val rect = Rect().also(window::getBoundsInScreen)
            GestureTapBounds(rect.left, rect.top, rect.right, rect.bottom)
        }.filter { it.right > it.left && it.bottom > it.top }
        return PackageTouchContext(GestureTapBounds(bounds.left, bounds.top, bounds.right, bounds.bottom), blockers)
    }

    private data class PackageTouchContext(val window: GestureTapBounds, val blockers: List<GestureTapBounds>)

    // 使用系统窗口与根节点的交集校验目标，避免分屏重排后使用旧边界。
    private fun findPackageWindowBounds(packageName: String, windowId: Int? = null): Rect? {
        service.windows.orEmpty().forEach { window ->
            if (windowId != null && window.id != windowId) return@forEach
            val rootNode = window.root ?: return@forEach
            if (rootNode.packageName?.toString() != packageName) {
                return@forEach
            }
            val bounds = Rect().also(rootNode::getBoundsInScreen)
            val windowBounds = Rect().also(window::getBoundsInScreen)
            if (bounds.intersect(windowBounds) &&
                bounds.width() > 0 && bounds.height() > 0
            ) {
                return bounds
            }
        }
        return null
    }

    private data class TapRequest(
        val x: Int,
        val y: Int,
        val durationMillis: Long,
        val packageName: String = "",
        val windowId: Int? = null,
        val endPoint: GestureTapPoint? = null,
        val focusPoint: GestureTapPoint? = null,
        val secondTapStartMillis: Long = 0L,
        val focusPrimed: Boolean = false,
        val onCompleted: ((Boolean) -> Unit)? = null
    )

    private companion object {
        const val STANDARD_TAP_DURATION_MILLIS = 16L
        const val PACKAGE_TAP_DURATION_MILLIS = 80L
        const val PACKAGE_FOCUS_SETTLE_DELAY_MILLIS = 80L
        const val COMMAND_SETTLE_MILLIS = 350L
        const val GESTURE_CALLBACK_TIMEOUT_MILLIS = 5_000L
    }
}

internal class GestureDispatchLifecycle {
    private var sequence = 0L
    private var active: Long? = null

    // 为每次真实派发生成唯一代次。
    fun begin(): Long = (++sequence).also { active = it }

    // 完成、取消、超时只能有一个生效，旧回调不得释放新手势的锁。
    fun finish(token: Long): Boolean {
        if (active != token) return false
        active = null
        return true
    }

    // 服务结束时使系统随后送达的旧回调失效。
    fun invalidate() {
        active = null
    }
}

internal class ProviderGestureTurnQueue(
    private val staleAfterMillis: Long = DEFAULT_STALE_AFTER_MILLIS
) {
    private val waitingProviders = linkedMapOf<MapProvider, Long>()

    // 清空服务生命周期结束前尚未取得执行权的 Provider。
    fun clear() {
        waitingProviders.clear()
    }

    // 撤回不再需要实际命令的地图等待票据。
    fun cancel(provider: MapProvider) {
        waitingProviders.remove(provider)
    }

    // 按首次等待顺序发放共享手势执行权并清理已经停止轮询的旧请求。
    fun canAcquire(
        provider: MapProvider,
        busy: Boolean,
        nowMillis: Long,
        consume: Boolean = true
    ): Boolean {
        removeStaleWaiters(nowMillis)
        if (busy) {
            waitingProviders[provider] = nowMillis
            return false
        }
        val nextProvider = waitingProviders.keys.firstOrNull()
        if (nextProvider != null && nextProvider != provider) {
            waitingProviders[provider] = nowMillis
            return false
        }
        if (consume) {
            waitingProviders.remove(provider)
        }
        return true
    }

    // 移除超过多个 Worker 轮询周期仍未重试的失效等待者。
    private fun removeStaleWaiters(nowMillis: Long) {
        waitingProviders.entries.removeAll { entry ->
            nowMillis < entry.value ||
                    nowMillis - entry.value > staleAfterMillis
        }
    }

    private companion object {
        const val DEFAULT_STALE_AFTER_MILLIS = 3_000L
    }
}

internal object PackageGestureTimingPolicy {

    // 校验第二次点击起点，确保两次按压不重叠且零值表示单击。
    fun secondTapStartMillis(
        requestedDelayMillis: Long,
        tapDurationMillis: Long
    ): Long {
        if (requestedDelayMillis <= 0L) {
            return 0L
        }
        return requestedDelayMillis.coerceAtLeast(tapDurationMillis)
    }
}

internal data class GestureTapPoint(
    val x: Int,
    val y: Int
)

internal object PackageGestureFocusPolicy {

    // 保留旧接口但禁用额外聚焦点击，窗口顶部可能是系统分屏操作条。
    @Suppress("UNUSED_PARAMETER")
    fun select(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): GestureTapPoint? {
        return null
    }
}

internal data class GestureTapBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

internal data class GestureSwipePath(val start: GestureTapPoint, val end: GestureTapPoint)

internal object BaiduPanelSwipePolicy {

    // 从统计标题的安全可触摸区域上拉，所有坐标随当前窗格变化，不使用固定屏幕位置。
    fun select(anchor: GestureTapBounds, window: GestureTapBounds, blockers: List<GestureTapBounds>): GestureSwipePath? {
        val start = PackageGestureTargetPolicy.select(anchor, window, blockers) ?: return null
        val height = window.bottom - window.top
        val endY = maxOf(window.top + height / 10,
            minOf(window.top + height * 35 / 100, start.y - height * 40 / 100))
        val end = GestureTapPoint(start.x, endY)
        if (start.y - end.y < height / 5 || height < 200) return null
        return GestureSwipePath(start, end).takeIf {
            PackageGestureTargetPolicy.isSafeGesture(start, end, window, blockers)
        }
    }
}

internal object PackageGestureTargetPolicy {

    // 不只检查两个端点，竖直拖动的整段路径都不能穿过分屏控件、键盘或其他覆盖窗口。
    fun isSafeGesture(start: GestureTapPoint, end: GestureTapPoint?, window: GestureTapBounds,
                      blockers: List<GestureTapBounds>): Boolean {
        if (!isSafePoint(start, window, blockers)) return false
        if (end == null) return true
        if (start.x != end.x || !isSafePoint(end, window, blockers)) return false
        val top = minOf(start.y, end.y)
        val bottom = maxOf(start.y, end.y)
        return blockers.none { start.x >= it.left && start.x < it.right && bottom >= it.top && top < it.bottom }
    }

    // 从按钮与窗口交集中扣除系统遮挡，选择面积最大的剩余矩形中心，不硬编码机型坐标。
    fun select(target: GestureTapBounds, window: GestureTapBounds, blockers: List<GestureTapBounds>): GestureTapPoint? {
        val clipped = intersection(target, window) ?: return null
        var regions = listOf(clipped)
        for (blocker in blockers) {
            regions = regions.flatMap { region -> subtract(region, blocker) }
        }
        val selected = regions.filter { it.right - it.left >= 4 && it.bottom - it.top >= 4 }
            .maxByOrNull { (it.right - it.left).toLong() * (it.bottom - it.top) } ?: return null
        return GestureTapPoint(selected.left + (selected.right - selected.left) / 2,
            selected.top + (selected.bottom - selected.top) / 2)
    }

    // 派发前再次检查动态窗口和遮挡，位置变化后拒绝沿用旧点击点。
    fun isSafePoint(point: GestureTapPoint, window: GestureTapBounds, blockers: List<GestureTapBounds>): Boolean {
        return contains(window, point) && blockers.none { contains(it, point) }
    }

    // 使用左闭右开坐标检查点是否属于系统热区或目标窗口。
    private fun contains(bounds: GestureTapBounds, point: GestureTapPoint): Boolean {
        return point.x >= bounds.left && point.x < bounds.right && point.y >= bounds.top && point.y < bounds.bottom
    }

    // 求有效矩形交集，忽略窗口外或负面积区域。
    private fun intersection(first: GestureTapBounds, second: GestureTapBounds): GestureTapBounds? {
        val result = GestureTapBounds(maxOf(first.left, second.left), maxOf(first.top, second.top),
            minOf(first.right, second.right), minOf(first.bottom, second.bottom))
        return result.takeIf { it.right > it.left && it.bottom > it.top }
    }

    // 将被系统遮挡的矩形拆成互不重叠的上下左右剩余区域。
    private fun subtract(region: GestureTapBounds, blocker: GestureTapBounds): List<GestureTapBounds> {
        val covered = intersection(region, blocker) ?: return listOf(region)
        return listOf(GestureTapBounds(region.left, region.top, region.right, covered.top),
            GestureTapBounds(region.left, covered.bottom, region.right, region.bottom),
            GestureTapBounds(region.left, covered.top, covered.left, covered.bottom),
            GestureTapBounds(covered.right, covered.top, region.right, covered.bottom))
            .filter { it.right > it.left && it.bottom > it.top }
    }
}

internal object PackageNavigationSafetyPolicy {

    // 未知焦点或焦点属于另一应用时拒绝全局返回。
    fun canNavigateBack(targetPackage: String, focusedPackage: String?): Boolean {
        return targetPackage.isNotBlank() && targetPackage == focusedPackage
    }
}

internal class ProviderNavigationLease {

    private var owner: MapProvider? = null
    private var deadlineMillis = 0L

    // 为一个已提交的跳转建立有期限的独占执行权。
    fun reserve(provider: MapProvider, untilMillis: Long) {
        owner = provider
        deadlineMillis = untilMillis
    }

    // 仅在跳转尚未到期时阻止其他地图，不阻断当前地图的结果读取。
    fun isBlocked(provider: MapProvider, nowMillis: Long): Boolean {
        return owner != null && owner != provider && nowMillis < deadlineMillis
    }

    // 只释放自己的执行权，迟到的旧地图回调不得释放另一地图的新跳转。
    fun release(provider: MapProvider) {
        if (owner == provider) clear()
    }

    // 服务停止或重连时清除旧跳转执行权。
    fun clear() {
        owner = null
        deadlineMillis = 0L
    }
}
