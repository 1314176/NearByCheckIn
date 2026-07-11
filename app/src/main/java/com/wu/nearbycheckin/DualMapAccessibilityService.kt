// @Time: 2026/07/02 01:54
// @Author: MrWu
// @File: DualMapAccessibilityService.kt
// @Software: Android Studio


@file:Suppress("SpellCheckingInspection")
@file:android.annotation.SuppressLint("AccessibilityPolicy", "AccessibilityService")


package com.wu.nearbycheckin


import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent

import androidx.core.net.toUri

/**
 * 只读采集百度地图和高德地图分屏窗口信息，不执行任何点击或手势操作。
 */
class DualMapAccessibilityService : AccessibilityService() {

    private var lastInspectionTimeMillis = 0L
    private var lastSnapshotSignature = ""
    private var pendingMapCheckInOperation = false
    private var mapCheckInOperationStarted = false
    private var autoSplitSequenceRunning = false
    private val mainHandler = Handler(
        Looper.getMainLooper()
    )
    private val diagnosticsRepository by lazy {
        AccessibilityDiagnosticsRepository(
            applicationContext
        )
    }
    private val autoSplitRequestRunnable = object : Runnable {
        override fun run() {
            consumeAutoSplitLaunchRequestIfNeeded()

            mainHandler.postDelayed(
                this,
                AUTO_SPLIT_REQUEST_POLL_INTERVAL_MILLIS
            )
        }
    }
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            inspectCurrentWindows(
                "诊断心跳"
            )

            mainHandler.postDelayed(
                this,
                HEARTBEAT_LOG_INTERVAL_MILLIS
            )
        }
    }

    /**
     * 无障碍服务连接后立即输出一次当前窗口诊断。
     */
    override fun onServiceConnected() {
        super.onServiceConnected()

        Log.i(
            TAG,
            "双地图只读诊断无障碍服务已连接"
        )

        inspectCurrentWindows(
            "服务连接"
        )

        mainHandler.post(
            autoSplitRequestRunnable
        )

        mainHandler.postDelayed(
            heartbeatRunnable,
            HEARTBEAT_LOG_INTERVAL_MILLIS
        )
    }

    /**
     * 接收窗口和内容变化事件，并按节流策略输出诊断日志。
     *
     * @param event 系统无障碍事件。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventType = event?.eventType
            ?: return

        if (eventType !in DIAGNOSTIC_EVENT_TYPES) {
            return
        }

        val now = SystemClock.elapsedRealtime()

        if (now - lastInspectionTimeMillis < INSPECTION_THROTTLE_MILLIS) {
            return
        }

        val eventPackageName = event.packageName?.toString().orEmpty()

        if (
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventPackageName !in TARGET_MAP_PACKAGE_NAMES
        ) {
            return
        }

        lastInspectionTimeMillis = now

        inspectCurrentWindows(
            "事件=$eventType，package=$eventPackageName"
        )
    }

    /**
     * 无障碍服务中断时记录状态，便于确认诊断能力是否仍可用。
     */
    override fun onInterrupt() {
        Log.w(
            TAG,
            "双地图只读诊断无障碍服务被系统中断"
        )
    }

    /**
     * 无障碍服务销毁时停止诊断心跳。
     */
    override fun onDestroy() {
        mainHandler.removeCallbacks(
            autoSplitRequestRunnable
        )
        mainHandler.removeCallbacks(
            heartbeatRunnable
        )

        pendingMapCheckInOperation = false
        mapCheckInOperationStarted = false
        autoSplitSequenceRunning = false

        super.onDestroy()
    }

    /**
     * 输出当前无障碍服务可见的交互窗口信息。
     *
     * @param reason 本次诊断的触发原因。
     */
    private fun inspectCurrentWindows(reason: String) {
        val snapshot = AccessibilityWindowInspector.inspectWindows(
            windows = windows.orEmpty(),
            reason = reason
        )

        diagnosticsRepository.saveSnapshot(
            snapshot
        )

        maybeStartMapCheckInOperation(
            snapshot
        )

        val snapshotSignature = buildSnapshotSignature(
            snapshot
        )

        if (
            snapshotSignature == lastSnapshotSignature &&
            reason != HEARTBEAT_REASON
        ) {
            return
        }

        lastSnapshotSignature = snapshotSignature

        AccessibilityWindowInspector.logSnapshot(
            snapshot
        )
    }

    /**
     * 消费主界面发起的自动双地图分屏请求。
     */
    private fun consumeAutoSplitLaunchRequestIfNeeded() {
        val requestTimeMillis =
            diagnosticsRepository.consumeAutoSplitLaunchRequest()
                ?: return

        if (
            System.currentTimeMillis() - requestTimeMillis >
            AUTO_SPLIT_REQUEST_EXPIRE_MILLIS
        ) {
            diagnosticsRepository.saveAutoSplitLaunchStatus(
                "自动分屏请求已过期，请重新开始自动化"
            )
            return
        }

        if (autoSplitSequenceRunning) {
            diagnosticsRepository.saveAutoSplitLaunchStatus(
                "自动分屏正在执行，已忽略重复请求"
            )
            return
        }

        startAutoSplitLaunchSequence()
    }

    /**
     * 顺序启动百度地图、系统分屏和高德地图，不强制指定上下窗口位置。
     */
    private fun startAutoSplitLaunchSequence() {
        pendingMapCheckInOperation = false
        mapCheckInOperationStarted = false
        autoSplitSequenceRunning = true

        diagnosticsRepository.saveAutoSplitLaunchStatus(
            "正在启动百度地图作为分屏锚点"
        )

        if (!launchMapApplication(BAIDU_MAP_PACKAGE_NAME, false) && !launchBaiduCruiseMode()) {
            autoSplitSequenceRunning = false

            diagnosticsRepository.saveAutoSplitLaunchStatus(
                "自动分屏失败：无法启动百度地图"
            )
            return
        }

        scheduleBaiduForegroundSplitAttempt(
            attempt = 1
        )
    }

    /**
     * 等待百度地图进入前台后触发系统分屏动作。
     *
     * @param attempt 当前等待和触发次数。
     */
    private fun scheduleBaiduForegroundSplitAttempt(attempt: Int) {
        mainHandler.postDelayed(
            {
                inspectCurrentWindows(
                    "等待百度地图前台，第${attempt}次检查"
                )

                if (isPackageInReducedWindow(BAIDU_MAP_PACKAGE_NAME)) {
                    launchAmapIntoAdjacentWindow()
                    return@postDelayed
                }

                if (!isPackageWindowVisible(BAIDU_MAP_PACKAGE_NAME)) {
                    if (attempt >= BAIDU_FOREGROUND_MAX_ATTEMPTS) {
                        autoSplitSequenceRunning = false
                        diagnosticsRepository.saveAutoSplitLaunchStatus(
                            "自动分屏失败：未识别到百度地图前台窗口"
                        )
                        return@postDelayed
                    }

                    scheduleBaiduForegroundSplitAttempt(
                        attempt = attempt + 1
                    )
                    return@postDelayed
                }

                val splitStarted = performGlobalAction(
                    GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
                )

                if (!splitStarted) {
                    if (attempt >= BAIDU_FOREGROUND_MAX_ATTEMPTS) {
                        autoSplitSequenceRunning = false
                        diagnosticsRepository.saveAutoSplitLaunchStatus(
                            "自动分屏失败：系统未接受分屏动作"
                        )
                        return@postDelayed
                    }

                    diagnosticsRepository.saveAutoSplitLaunchStatus(
                        "系统分屏动作未确认，继续等待百度地图可分屏"
                    )

                    scheduleBaiduForegroundSplitAttempt(
                        attempt = attempt + 1
                    )
                    return@postDelayed
                }

                diagnosticsRepository.saveAutoSplitLaunchStatus(
                    "系统分屏动作已发出，等待百度地图变为半屏"
                )

                scheduleBaiduSplitWindowInspection(
                    attempt = 1
                )
            },
            BAIDU_FOREGROUND_RETRY_DELAY_MILLIS
        )
    }

    /**
     * 确认百度地图窗口已经缩小为分屏窗口后，再启动高德地图到相邻窗口。
     *
     * @param attempt 当前分屏确认次数。
     */
    private fun scheduleBaiduSplitWindowInspection(attempt: Int) {
        mainHandler.postDelayed(
            {
                inspectCurrentWindows(
                    "等待百度地图半屏，第${attempt}次检查"
                )

                if (isPackageInReducedWindow(BAIDU_MAP_PACKAGE_NAME)) {
                    launchAmapIntoAdjacentWindow()
                    return@postDelayed
                }

                if (attempt >= BAIDU_SPLIT_CONFIRM_MAX_ATTEMPTS) {
                    autoSplitSequenceRunning = false
                    diagnosticsRepository.saveAutoSplitLaunchStatus(
                        "自动分屏失败：百度地图没有进入单屏双窗口"
                    )
                    return@postDelayed
                }

                scheduleBaiduSplitWindowInspection(
                    attempt = attempt + 1
                )
            },
            BAIDU_SPLIT_CONFIRM_RETRY_DELAY_MILLIS
        )
    }

    /**
     * 在百度地图已分屏后启动高德地图到相邻窗口。
     */
    private fun launchAmapIntoAdjacentWindow() {
        val amapStarted = launchMapApplication(
            AMAP_PACKAGE_NAME,
            true
        )

        if (!amapStarted) {
            autoSplitSequenceRunning = false
            diagnosticsRepository.saveAutoSplitLaunchStatus(
                "自动分屏失败：无法启动高德地图"
            )
            return
        }

        diagnosticsRepository.saveAutoSplitLaunchStatus(
            "百度地图已半屏，已启动高德地图到相邻窗口，等待双窗口稳定后执行地图打卡"
        )

        pendingMapCheckInOperation = true
        mapCheckInOperationStarted = false

        inspectCurrentWindows(
            "等待双地图窗口稳定"
        )

        scheduleMapCheckInReadinessInspection(
            attempt = 1
        )
    }

    /**
     * 安排双地图节点打卡流程，等待地图页面完成加载后执行。
     */
    private fun scheduleDualMapCheckInOperation() {
        mainHandler.postDelayed(
            {
                executeBaiduCheckInStep(
                    stepIndex = 0
                )
            },
            MAP_OPERATION_INITIAL_DELAY_MILLIS
        )
    }

    /**
     * 周期性复查双地图窗口状态，避免窗口事件被系统节流后无法触发打卡流程。
     *
     * @param attempt 当前复查次数。
     */
    private fun scheduleMapCheckInReadinessInspection(attempt: Int) {
        if (
            !pendingMapCheckInOperation ||
            mapCheckInOperationStarted
        ) {
            return
        }

        if (attempt > MAP_CHECK_IN_READINESS_MAX_ATTEMPTS) {
            autoSplitSequenceRunning = false

            diagnosticsRepository.saveAutoSplitLaunchStatus(
                "自动分屏未稳定：未同时识别到百度地图和高德地图窗口"
            )
            return
        }

        mainHandler.postDelayed(
            {
                inspectCurrentWindows(
                    "等待双地图窗口稳定，第${attempt}次复查"
                )

                scheduleMapCheckInReadinessInspection(
                    attempt = attempt + 1
                )
            },
            MAP_CHECK_IN_READINESS_RETRY_DELAY_MILLIS
        )
    }

    /**
     * 在双地图窗口都被识别后启动节点打卡流程，避免分屏尚未稳定时提前点击。
     *
     * @param snapshot 本次窗口诊断得到的双地图状态。
     */
    private fun maybeStartMapCheckInOperation(
        snapshot: AccessibilityDiagnosticsSnapshot
    ) {
        if (
            !pendingMapCheckInOperation ||
            mapCheckInOperationStarted ||
            !snapshot.baiduReady ||
            !snapshot.amapReady
        ) {
            return
        }

        pendingMapCheckInOperation = false
        mapCheckInOperationStarted = true
        autoSplitSequenceRunning = false

        diagnosticsRepository.saveAutoSplitLaunchStatus(
            "双地图窗口已就绪，准备执行地图打卡"
        )

        scheduleDualMapCheckInOperation()
    }

    /**
     * 按“我的-足迹-打卡”顺序执行百度地图节点点击。
     *
     * @param stepIndex 当前步骤序号。
     */
    private fun executeBaiduCheckInStep(stepIndex: Int) {
        val rootNode = findWindowRootNode(
            BAIDU_MAP_PACKAGE_NAME
        )

        val result = when (stepIndex) {
            0 -> BaiduMapAdapter.clickMine(rootNode)
            1 -> BaiduMapAdapter.clickFootprint(rootNode)
            2 -> BaiduMapAdapter.clickCheckIn(rootNode)
            else -> {
                executeAmapCheckInStep(
                    stepIndex = 0
                )
                return
            }
        }

        Log.i(
            TAG,
            "百度地图打卡步骤${stepIndex + 1}：${result.message}"
        )

        if (!result.success) {
            diagnosticsRepository.saveAutoSplitLaunchStatus(
                "百度地图打卡失败：${result.message}"
            )
            return
        }

        diagnosticsRepository.saveAutoSplitLaunchStatus(
            "百度地图打卡步骤${stepIndex + 1}完成"
        )

        mainHandler.postDelayed(
            {
                executeBaiduCheckInStep(
                    stepIndex = stepIndex + 1
                )
            },
            MAP_OPERATION_STEP_DELAY_MILLIS
        )
    }

    /**
     * 按“我的-打卡点-立刻打卡”顺序执行高德地图节点点击。
     *
     * @param stepIndex 当前步骤序号。
     */
    private fun executeAmapCheckInStep(stepIndex: Int) {
        val rootNode = findWindowRootNode(
            AMAP_PACKAGE_NAME
        )

        val result = when (stepIndex) {
            0 -> AmapMapAdapter.clickMine(rootNode)
            1 -> AmapMapAdapter.clickCheckInPoint(rootNode)
            2 -> AmapMapAdapter.clickImmediateCheckIn(rootNode)
            else -> {
                diagnosticsRepository.saveAutoSplitLaunchStatus(
                    "百度地图和高德地图打卡流程已执行，等待地图端结果确认"
                )
                return
            }
        }

        Log.i(
            TAG,
            "高德地图打卡步骤${stepIndex + 1}：${result.message}"
        )

        if (!result.success) {
            diagnosticsRepository.saveAutoSplitLaunchStatus(
                "高德地图打卡失败：${result.message}"
            )
            return
        }

        diagnosticsRepository.saveAutoSplitLaunchStatus(
            "高德地图打卡步骤${stepIndex + 1}完成"
        )

        mainHandler.postDelayed(
            {
                executeAmapCheckInStep(
                    stepIndex = stepIndex + 1
                )
            },
            MAP_OPERATION_STEP_DELAY_MILLIS
        )
    }

    /**
     * 查找指定包名对应窗口的根节点。
     *
     * @param packageName 目标地图应用包名。
     * @return 对应窗口根节点；找不到时返回null。
     */
    private fun findWindowRootNode(packageName: String): android.view.accessibility.AccessibilityNodeInfo? {
        return windows.orEmpty()
            .mapNotNull { window ->
                window.root
            }
            .firstOrNull { rootNode ->
                containsPackageName(
                    node = rootNode,
                    packageName = packageName,
                    depth = 0
                )
            }
    }

    /**
     * 判断指定包名窗口当前是否已被无障碍服务识别。
     *
     * @param packageName 目标地图应用包名。
     * @return 已识别到对应窗口时返回true。
     */
    private fun isPackageWindowVisible(packageName: String): Boolean {
        return windows.orEmpty().any { window ->
            containsPackageName(
                node = window.root,
                packageName = packageName,
                depth = 0
            )
        }
    }

    /**
     * 判断指定包名窗口是否已经缩小为分屏窗口。
     *
     * @param packageName 目标地图应用包名。
     * @return 窗口宽度或高度明显小于整屏时返回true。
     */
    private fun isPackageInReducedWindow(packageName: String): Boolean {
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels

        return windows.orEmpty().any { window ->
            val rootNode = window.root

            if (
                !containsPackageName(
                    node = rootNode,
                    packageName = packageName,
                    depth = 0
                )
            ) {
                return@any false
            }

            val bounds = Rect()
            window.getBoundsInScreen(
                bounds
            )

            val reducedWidth =
                bounds.width() in 1..(screenWidth * SPLIT_WINDOW_SIZE_PERCENT / 100)

            val reducedHeight =
                bounds.height() in 1..(screenHeight * SPLIT_WINDOW_SIZE_PERCENT / 100)

            reducedWidth || reducedHeight
        }
    }

    /**
     * 递归判断节点树是否属于指定包名。
     *
     * @param node 当前节点。
     * @param packageName 目标包名。
     * @param depth 当前递归深度。
     * @return 节点树包含目标包名时返回true。
     */
    private fun containsPackageName(
        node: android.view.accessibility.AccessibilityNodeInfo?,
        packageName: String,
        depth: Int
    ): Boolean {
        if (
            node == null ||
            depth > PACKAGE_SCAN_DEPTH
        ) {
            return false
        }

        if (node.packageName?.toString() == packageName) {
            return true
        }

        for (childIndex in 0 until node.childCount) {
            if (
                containsPackageName(
                    node = node.getChild(childIndex),
                    packageName = packageName,
                    depth = depth + 1
                )
            ) {
                return true
            }
        }

        return false
    }

    /**
     * 使用百度地图URI启动巡航模式，失败时回退到包名启动。
     */
    private fun launchBaiduCruiseMode(): Boolean {
        val uriIntent = Intent(
            Intent.ACTION_VIEW,
            BAIDU_CRUISE_URI.toUri()
        ).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
            )
        }

        return try {
            startActivity(
                uriIntent
            )

            true
        } catch (exception: RuntimeException) {
            Log.w(
                TAG,
                "百度巡航URI启动失败，回退包名启动",
                exception
            )

            launchMapApplication(
                packageName = BAIDU_MAP_PACKAGE_NAME,
                launchAdjacent = false
            )
        }
    }

    /**
     * 启动指定地图应用，可选请求进入相邻分屏窗口。
     *
     * @param packageName 目标地图应用包名。
     * @param launchAdjacent 是否请求系统在相邻分屏窗口启动。
     * @return 成功发送启动请求时返回true。
     */
    private fun launchMapApplication(
        packageName: String,
        launchAdjacent: Boolean
    ): Boolean {
        val launchIntent = packageManager.getLaunchIntentForPackage(
            packageName
        ) ?: return false

        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK
        )

        if (launchAdjacent) {
            launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            )
        }

        return try {
            startActivity(
                launchIntent
            )

            true
        } catch (exception: RuntimeException) {
            Log.e(
                TAG,
                "启动地图应用失败：$packageName",
                exception
            )

            false
        }
    }

    /**
     * 生成窗口状态签名，用于避免状态未变化时重复刷日志。
     *
     * @param snapshot 本次诊断摘要。
     * @return 可用于比较状态变化的字符串签名。
     */
    private fun buildSnapshotSignature(
        snapshot: AccessibilityDiagnosticsSnapshot
    ): String {
        return "${snapshot.baiduReady}|" +
                "${snapshot.amapReady}|" +
                "${snapshot.totalWindowCount}|" +
                "${snapshot.targetWindowCount}|" +
                "${snapshot.targetNodeCount}|" +
                "${snapshot.baiduClickableNodeCount}|" +
                "${snapshot.baiduCandidateSummary}|" +
                "${snapshot.amapClickableNodeCount}|" +
                snapshot.amapCandidateSummary
    }

    private companion object {
        const val TAG = "DualMapA11yService"
        const val INSPECTION_THROTTLE_MILLIS = 5_000L
        const val HEARTBEAT_LOG_INTERVAL_MILLIS = 30_000L
        const val HEARTBEAT_REASON = "诊断心跳"
        const val BAIDU_MAP_PACKAGE_NAME = "com.baidu.BaiduMap"
        const val AMAP_PACKAGE_NAME = "com.autonavi.minimap"
        const val BAIDU_CRUISE_URI =
            "baidumap://map/navi/cruiser?src=andr.wu.nearbycheckin"
        const val AUTO_SPLIT_REQUEST_EXPIRE_MILLIS = 120_000L
        const val AUTO_SPLIT_REQUEST_POLL_INTERVAL_MILLIS = 1_000L
        const val BAIDU_FOREGROUND_RETRY_DELAY_MILLIS = 1_000L
        const val BAIDU_FOREGROUND_MAX_ATTEMPTS = 8
        const val BAIDU_SPLIT_CONFIRM_RETRY_DELAY_MILLIS = 1_000L
        const val BAIDU_SPLIT_CONFIRM_MAX_ATTEMPTS = 8
        const val MAP_OPERATION_INITIAL_DELAY_MILLIS = 4_000L
        const val MAP_OPERATION_STEP_DELAY_MILLIS = 1_500L
        const val MAP_CHECK_IN_READINESS_RETRY_DELAY_MILLIS = 1_500L
        const val MAP_CHECK_IN_READINESS_MAX_ATTEMPTS = 8
        const val SPLIT_WINDOW_SIZE_PERCENT = 75
        const val PACKAGE_SCAN_DEPTH = 4

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
