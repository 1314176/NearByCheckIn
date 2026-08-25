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
    private var isDispatching = false
    private var commandReservedUntilMillis = 0L

    // 判断共享命令闸门当前是否允许领取新的 UI 任务。
    fun canExecuteCommand(provider: MapProvider): Boolean {
        val nowMillis = SystemClock.elapsedRealtime()
        return providerTurnQueue.canAcquire(
            provider = provider,
            busy = isDispatching || nowMillis < commandReservedUntilMillis,
            nowMillis = nowMillis
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
                        nowMillis < commandReservedUntilMillis,
                nowMillis = nowMillis
            )
        ) {
            return null
        }
        commandReservedUntilMillis = nowMillis + COMMAND_SETTLE_MILLIS
        return block()
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

    // 为目标应用原子提交聚焦与点击，避免另一地图在队列间隙抢走焦点。
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

    // 依据目标窗口生成安全聚焦点并提交不可插队的应用手势请求。
    private fun enqueuePackageTap(
        x: Int,
        y: Int,
        packageName: String,
        secondTapDelayMillis: Long = 0L,
        onCompleted: ((Boolean) -> Unit)? = null
    ): Boolean {
        val focusPoint = findPackageWindowBounds(packageName)?.let { bounds ->
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

    // 清空尚未派发的手势并等待系统结束当前手势。
    fun clear() {
        mainHandler.removeCallbacksAndMessages(null)
        pendingTaps.clear()
        providerTurnQueue.clear()
    }

    // 派发队首点击并在系统回调后继续处理下一条。
    private fun dispatchNext(): Boolean {
        if (pendingTaps.isEmpty()) {
            isDispatching = false
            return false
        }
        val tap = pendingTaps.removeFirst()
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
        val accepted = service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {

                // 在手势完成后释放仲裁器并派发下一条。
                override fun onCompleted(
                    gestureDescription: GestureDescription?
                ) {
                    if (!isFocusPrimer) {
                        tap.onCompleted?.invoke(true)
                    }
                    continueDispatchAfterGesture(isFocusPrimer)
                }

                // 在手势取消后释放仲裁器并继续后续队列。
                override fun onCancelled(
                    gestureDescription: GestureDescription?
                ) {
                    if (!isFocusPrimer) {
                        tap.onCompleted?.invoke(false)
                    }
                    isDispatching = false
                    dispatchNext()
                }
            },
            null
        )
        if (!accepted) {
            if (!isFocusPrimer) {
                tap.onCompleted?.invoke(false)
            }
            isDispatching = false
            dispatchNext()
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

    // 查找目标应用窗口边界，为无副作用聚焦点提供运行时依据。
    private fun findPackageWindowBounds(packageName: String): Rect? {
        service.windows.orEmpty().forEach { window ->
            val rootNode = window.root ?: return@forEach
            if (rootNode.packageName?.toString() != packageName) {
                return@forEach
            }
            val bounds = Rect().also(rootNode::getBoundsInScreen)
            if (bounds.width() > 0 && bounds.height() > 0) {
                return bounds
            }
        }
        return null
    }

    private data class TapRequest(
        val x: Int,
        val y: Int,
        val durationMillis: Long,
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

    // 按首次等待顺序发放共享手势执行权并清理已经停止轮询的旧请求。
    fun canAcquire(
        provider: MapProvider,
        busy: Boolean,
        nowMillis: Long
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
        waitingProviders.remove(provider)
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

    // 在目标窗口顶部中央选择不与流程按钮重叠的安全聚焦点。
    fun select(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): GestureTapPoint? {
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) {
            return null
        }
        val topOffset = (height * FOCUS_Y_PERCENT / 100)
            .coerceIn(MIN_FOCUS_Y_OFFSET, MAX_FOCUS_Y_OFFSET)
            .coerceAtMost(height - 1)
        return GestureTapPoint(
            x = left + width / 2,
            y = top + topOffset
        )
    }

    private const val FOCUS_Y_PERCENT = 5
    private const val MIN_FOCUS_Y_OFFSET = 24
    private const val MAX_FOCUS_Y_OFFSET = 64
}
