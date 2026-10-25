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
        onCompleted: ((Boolean) -> Unit)? = null
    ): Boolean {
        return enqueuePackageTap(
            x = x,
            y = y,
            packageName = packageName,
            onCompleted = onCompleted
        )
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
        onCompleted: ((Boolean) -> Unit)? = null
    ): Boolean {
        val bounds = findPackageWindowBounds(packageName) ?: return false
        if (!bounds.contains(x, y)) return false
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
        if (tap.packageName.isNotBlank() &&
            findPackageWindowBounds(tap.packageName)?.contains(tap.x, tap.y) != true
        ) {
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

    // 使用系统窗口与根节点的交集校验目标，避免分屏重排后使用旧边界。
    private fun findPackageWindowBounds(packageName: String): Rect? {
        service.windows.orEmpty().forEach { window ->
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
