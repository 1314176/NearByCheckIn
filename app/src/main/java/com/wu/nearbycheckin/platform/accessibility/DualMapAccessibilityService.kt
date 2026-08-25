// @Time: 2026/07/20 18:42
// @Author: MrWu
// @File: DualMapAccessibilityService.kt
// @Software: Android Studio


package com.wu.nearbycheckin.platform.accessibility


import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent

import androidx.core.content.ContextCompat

import com.wu.nearbycheckin.app.NearbyCheckInApplication
import com.wu.nearbycheckin.core.model.AccessibilityDiagnostics
import com.wu.nearbycheckin.core.model.AutomationLogLevel
import com.wu.nearbycheckin.core.model.MapProvider
import com.wu.nearbycheckin.core.model.AutomationSessionStatus
import com.wu.nearbycheckin.platform.cruise.CruiseService
import com.wu.nearbycheckin.platform.maps.AccessibilityWindowInspector


/**
 * 只负责无障碍生命周期、事件诊断和平台组件装配。
 */
class DualMapAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val appContainer by lazy {
        (application as NearbyCheckInApplication).appContainer
    }
    private lateinit var splitController: DualMapSplitController
    private lateinit var gestureTapDispatcher:
        AccessibilityGestureTapDispatcher
    private lateinit var baiduProviderWorker: ProviderAutomationWorker
    private lateinit var amapProviderWorker: ProviderAutomationWorker
    private var lastInspectionTimeMillis = 0L
    private var lastSnapshotSignature = ""

    private val autoSplitRequestRunnable = object : Runnable {

        // 轮询并消费一次用户发起的自动分屏请求。
        override fun run() {
            consumeAutoSplitLaunchRequest()
            mainHandler.postDelayed(
                this,
                AUTO_SPLIT_REQUEST_POLL_INTERVAL_MILLIS
            )
        }
    }

    private val heartbeatRunnable = object : Runnable {

        // 定时刷新窗口诊断并保持 UI 可观测。
        override fun run() {
            inspectCurrentWindows(HEARTBEAT_REASON)
            mainHandler.postDelayed(
                this,
                HEARTBEAT_LOG_INTERVAL_MILLIS
            )
        }
    }

    // 装配分屏控制器与命令 Worker 并恢复未完成事务。
    override fun onServiceConnected() {
        super.onServiceConnected()
        splitController = DualMapSplitController(
            service = this,
            inspectWindows = ::inspectCurrentWindows,
            onStatusChanged = ::saveAutomationStatus
        )
        gestureTapDispatcher = AccessibilityGestureTapDispatcher(this)
        baiduProviderWorker = ProviderAutomationWorker(
            service = this,
            repository = appContainer.transactionRepository,
            coordinator = appContainer.providerAutomationCoordinator,
            registry = appContainer.mapAutomationPluginRegistry,
            provider = MapProvider.BAIDU,
            gestureDispatcher = gestureTapDispatcher,
            onStatusChanged = ::saveAutomationStatus
        )
        amapProviderWorker = ProviderAutomationWorker(
            service = this,
            repository = appContainer.transactionRepository,
            coordinator = appContainer.providerAutomationCoordinator,
            registry = appContainer.mapAutomationPluginRegistry,
            provider = MapProvider.AMAP,
            gestureDispatcher = gestureTapDispatcher,
            onStatusChanged = ::saveAutomationStatus
        )

        saveAutomationStatus("双地图无障碍服务已连接")
        appContainer.checkInCoordinator.resumePendingTransactions()
        resumeCruiseServiceIfNeeded()
        inspectCurrentWindows("服务连接")
        baiduProviderWorker.start()
        amapProviderWorker.start(AMAP_WORKER_START_DELAY_MILLIS)
        mainHandler.post(autoSplitRequestRunnable)
        mainHandler.postDelayed(
            heartbeatRunnable,
            HEARTBEAT_LOG_INTERVAL_MILLIS
        )
    }

    // 在进程或安装更新后恢复仍处于运行状态的前台定位服务。
    private fun resumeCruiseServiceIfNeeded() {
        if (
            appContainer.transactionRepository.currentSnapshot()
                .session?.status != AutomationSessionStatus.RUNNING
        ) {
            return
        }
        runCatching {
            ContextCompat.startForegroundService(
                this,
                Intent(this, CruiseService::class.java)
            )
        }.onFailure { error ->
            Log.e(TAG, "自动恢复巡航定位服务失败", error)
            saveAutomationStatus("自动恢复巡航定位服务失败")
        }
    }

    // 按包名和时间节流窗口变化诊断。
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) {
            return
        }
        if (event.eventType in TRANSIENT_MAP_EVENT_TYPES) {
            forwardTransientMapMessage(event)
        }
        if (
            event.eventType ==
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_ANNOUNCEMENT
        ) {
            return
        }
        if (event.eventType !in DIAGNOSTIC_EVENT_TYPES) {
            return
        }
        val packageName = event.packageName?.toString().orEmpty()
        if (
            event.eventType ==
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            packageName !in TARGET_MAP_PACKAGE_NAMES
        ) {
            return
        }
        val nowMillis = SystemClock.elapsedRealtime()
        if (
            nowMillis - lastInspectionTimeMillis <
            INSPECTION_THROTTLE_MILLIS
        ) {
            return
        }
        lastInspectionTimeMillis = nowMillis
        inspectCurrentWindows("窗口变化")
    }

    // 把高德瞬时频率限制提示转交给当前命令并避免写入无关通知内容。
    private fun forwardTransientMapMessage(
        event: AccessibilityEvent
    ) {
        val packageName = event.packageName?.toString().orEmpty()
        val message = event.text
            .joinToString(separator = " ") { text ->
                text?.toString().orEmpty()
            }
            .plus(" ${event.contentDescription?.toString().orEmpty()}")
            .plus(" ${event.source?.text?.toString().orEmpty()}")
            .plus(
                " ${event.source?.contentDescription?.toString().orEmpty()}"
            )
            .trim()
        if (
            AmapTransientMessagePolicy.isRateLimitEvidence(
                packageName = packageName,
                message = message
            )
        ) {
            if (::amapProviderWorker.isInitialized) {
                amapProviderWorker.recordAmapRateLimitEvidence(message)
            }
            saveAutomationStatus("高德地图提示：$message")
        }
    }

    // 记录系统中断但不私自推进领域事务。
    override fun onInterrupt() {
        Log.w(TAG, "双地图无障碍服务被系统中断")
        saveAutomationStatus("双地图无障碍服务被系统中断")
    }

    // 停止轮询、分屏和命令执行组件。
    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        if (::splitController.isInitialized) {
            splitController.stop()
        }
        if (::baiduProviderWorker.isInitialized) {
            baiduProviderWorker.stop()
        }
        if (::amapProviderWorker.isInitialized) {
            amapProviderWorker.stop()
        }
        if (::gestureTapDispatcher.isInitialized) {
            gestureTapDispatcher.clear()
        }
        super.onDestroy()
    }

    // 消费有效分屏请求并避免与当前命令并发。
    private fun consumeAutoSplitLaunchRequest() {
        val requestTimeMillis =
            appContainer.transactionRepository
                .consumeAutoSplitLaunchRequest()
                ?: return
        val requestAgeMillis =
            System.currentTimeMillis() - requestTimeMillis
        if (requestAgeMillis > AUTO_SPLIT_REQUEST_EXPIRE_MILLIS) {
            saveAutomationStatus(
                "自动双地图请求已过期，请重新打开软件"
            )
            return
        }
        if (
            splitController.isRunning ||
            baiduProviderWorker.isBusy ||
            amapProviderWorker.isBusy
        ) {
            saveAutomationStatus(
                "双地图自动化正在执行，已忽略重复请求"
            )
            return
        }
        splitController.start()
    }

    // 采集窗口诊断并同步到唯一事实来源。
    private fun inspectCurrentWindows(
        reason: String
    ): AccessibilityDiagnostics {
        val snapshot = AccessibilityWindowInspector.inspectWindows(
            windows = windows.orEmpty(),
            reason = reason
        )
        appContainer.transactionRepository.updateDiagnostics(snapshot)

        val signature = buildSnapshotSignature(snapshot)
        if (
            signature != lastSnapshotSignature ||
            reason == HEARTBEAT_REASON
        ) {
            AccessibilityWindowInspector.logSnapshot(snapshot)
            lastSnapshotSignature = signature
        }
        return snapshot
    }

    // 将自动化状态写入 V4 唯一事实来源。
    private fun saveAutomationStatus(statusText: String) {
        appContainer.transactionRepository.updateAutomationStatus(
            statusText = statusText,
            source = "无障碍服务",
            level = statusText.toLogLevel()
        )
    }

    // 根据无障碍状态文本确定持久化日志级别。
    private fun String.toLogLevel(): AutomationLogLevel {
        val normalizedStatus = uppercase()
        return when {
            contains("失败") ||
                    contains("拒绝") ||
                    contains("中断") ||
                    "FAILED" in normalizedStatus ||
                    "ERROR" in normalizedStatus ||
                    "TIMEOUT" in normalizedStatus ->
                AutomationLogLevel.ERROR
            contains("过期") ||
                    contains("忽略") ||
                    contains("未") ||
                    "BLOCKED" in normalizedStatus ||
                    "UNSUPPORTED" in normalizedStatus ||
                    "UNKNOWN" in normalizedStatus ->
                AutomationLogLevel.WARNING
            else -> AutomationLogLevel.INFO
        }
    }

    // 构造忽略时间戳的诊断签名以减少重复日志。
    private fun buildSnapshotSignature(
        snapshot: AccessibilityDiagnostics
    ): String {
        return listOf(
            snapshot.baiduReady,
            snapshot.amapReady,
            snapshot.totalWindowCount,
            snapshot.targetWindowCount,
            snapshot.targetNodeCount,
            snapshot.baiduClickableNodeCount,
            snapshot.amapClickableNodeCount
        ).joinToString(separator = "|")
    }

    private companion object {
        const val AMAP_WORKER_START_DELAY_MILLIS = 500L
        const val TAG = "DualMapA11yService"
        const val BAIDU_MAP_PACKAGE_NAME = "com.baidu.BaiduMap"
        const val AMAP_PACKAGE_NAME = "com.autonavi.minimap"
        const val AUTO_SPLIT_REQUEST_EXPIRE_MILLIS = 120_000L
        const val AUTO_SPLIT_REQUEST_POLL_INTERVAL_MILLIS = 500L
        const val INSPECTION_THROTTLE_MILLIS = 1_000L
        const val HEARTBEAT_LOG_INTERVAL_MILLIS = 30_000L
        val TRANSIENT_MAP_EVENT_TYPES = setOf(
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED,
            AccessibilityEvent.TYPE_ANNOUNCEMENT,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        )
        const val HEARTBEAT_REASON = "诊断心跳"

        val DIAGNOSTIC_EVENT_TYPES = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED
        )
        val TARGET_MAP_PACKAGE_NAMES = setOf(
            BAIDU_MAP_PACKAGE_NAME,
            AMAP_PACKAGE_NAME
        )
    }
}
