// @Time: 2026/07/20 15:50
// @Author: MrWu
// @File: DualMapSplitController.kt
// @Software: Codex

package com.wu.nearbycheckin.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

import com.wu.nearbycheckin.domain.model.AccessibilityDiagnostics
import com.wu.nearbycheckin.platform.multiwindow.DualMapLaunchActivity

/**
 * 只负责建立百度和高德系统分屏窗口。
 */
class DualMapSplitController(
    private val service: AccessibilityService,
    private val inspectWindows:
            (String) -> AccessibilityDiagnostics,
    private val onStatusChanged: (String) -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    var isRunning: Boolean = false
        private set

    // 启动一次幂等的双地图分屏流程。
    fun start() {
        if (isRunning) {
            return
        }
        isRunning = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2) {
            Log.i(TAG, "等待前台 Activity 使用相邻窗口 API 创建双地图分屏")
            onStatusChanged("正在等待前台创建双地图相邻窗口")
            mainHandler.postDelayed(
                {
                    verifyDualMapWindows(attempt = 1)
                },
                DUAL_WINDOW_VERIFY_DELAY_MILLIS
            )
            return
        }
        onStatusChanged("正在确认百度地图窗口")
        mainHandler.post {
            ensureBaiduWindow(attempt = 1)
        }
    }

    // 停止当前分屏流程并清理延迟任务。
    fun stop() {
        mainHandler.removeCallbacksAndMessages(null)
        isRunning = false
    }

    // 确认百度窗口存在，否则按退避次数重新打开。
    private fun ensureBaiduWindow(attempt: Int) {
        val snapshot = inspectWindows("确认百度地图窗口")
        if (snapshot.baiduReady) {
            requestSystemSplitScreen()
            return
        }
        if (attempt == 1 || attempt in BAIDU_RETRY_ATTEMPTS) {
            if (!launchBaiduMap()) {
                finish("自动双地图失败：无法打开百度地图")
                return
            }
        }
        if (attempt < BAIDU_VERIFY_MAX_ATTEMPTS) {
            onStatusChanged("正在等待百度地图窗口：第${attempt}次")
            mainHandler.postDelayed(
                {
                    ensureBaiduWindow(attempt + 1)
                },
                BAIDU_VERIFY_DELAY_MILLIS
            )
            return
        }
        finish("自动双地图失败：未检测到百度地图窗口")
    }

    // 请求系统将当前百度窗口切入分屏。
    @Suppress("DEPRECATION")
    private fun requestSystemSplitScreen() {
        inspectWindows("请求系统分屏前")
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            service.systemActions.none { action ->
                action.id ==
                        AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
            }
        ) {
            Log.w(TAG, "系统动作列表不包含切换分屏")
            finish("系统不提供自动切换分屏动作，请使用前台相邻窗口启动")
            return
        }
        val accepted = service.performGlobalAction(
            AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
        )
        Log.i(TAG, "旧版系统分屏动作返回：$accepted")
        if (!accepted) {
            finish("系统拒绝自动切换分屏动作")
            return
        }
        onStatusChanged(
            "已请求系统分屏，准备打开高德地图"
        )
        mainHandler.postDelayed(
            {
                launchAmapInAdjacentWindow(attempt = 1)
            },
            AMAP_LAUNCH_AFTER_SPLIT_DELAY_MILLIS
        )
    }

    // 使用相邻窗口标记启动高德地图。
    private fun launchAmapInAdjacentWindow(attempt: Int) {
        val started = launchMapApplication(
            packageName = AMAP_PACKAGE_NAME,
            launchAdjacent = true
        )
        if (!started && attempt < AMAP_START_MAX_ATTEMPTS) {
            onStatusChanged(
                "高德地图相邻窗口启动失败，正在重试第${attempt + 1}次"
            )
            mainHandler.postDelayed(
                {
                    launchAmapInAdjacentWindow(attempt + 1)
                },
                AMAP_START_RETRY_DELAY_MILLIS
            )
            return
        }
        if (!started) {
            finish("自动双地图失败：无法打开高德地图")
            return
        }

        onStatusChanged("已打开高德地图，正在确认双窗口")
        mainHandler.postDelayed(
            {
                verifyDualMapWindows(attempt = 1)
            },
            DUAL_WINDOW_VERIFY_DELAY_MILLIS
        )
    }

    // 验证两个地图窗口都已进入无障碍窗口列表。
    private fun verifyDualMapWindows(attempt: Int) {
        val snapshot = inspectWindows("校验双地图窗口")
        if (snapshot.baiduReady && snapshot.amapReady) {
            finish("双地图分屏已就绪，等待事务命令")
            return
        }
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S_V2 &&
            !snapshot.amapReady &&
            attempt in AMAP_RETRY_ATTEMPTS
        ) {
            launchMapApplication(
                packageName = AMAP_PACKAGE_NAME,
                launchAdjacent = true
            )
        }
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S_V2 &&
            !snapshot.baiduReady &&
            attempt in BAIDU_RETRY_ATTEMPTS
        ) {
            launchBaiduMap()
        }
        if (attempt < DUAL_WINDOW_VERIFY_MAX_ATTEMPTS) {
            onStatusChanged(
                "正在等待双地图分屏：百度=${snapshot.baiduReady}，" +
                        "高德=${snapshot.amapReady}"
            )
            mainHandler.postDelayed(
                {
                    verifyDualMapWindows(attempt + 1)
                },
                DUAL_WINDOW_VERIFY_DELAY_MILLIS
            )
            return
        }

        finish(
            when {
                snapshot.baiduReady && !snapshot.amapReady ->
                    "自动双地图失败：只检测到百度地图窗口"
                !snapshot.baiduReady && snapshot.amapReady ->
                    "自动双地图失败：只检测到高德地图窗口"
                else ->
                    "自动双地图失败：未检测到百度和高德双窗口"
            }
        )
    }

    // 结束分屏流程并保存最终状态。
    private fun finish(statusText: String) {
        isRunning = false
        onStatusChanged(statusText)
    }

    // 打开百度地图并优先使用巡航协议入口。
    private fun launchBaiduMap(): Boolean {
        if (!isMapApplicationInstalled(BAIDU_MAP_PACKAGE_NAME)) {
            return false
        }
        return try {
            service.startActivity(
                DualMapLaunchActivity.createIntent(
                    context = service.applicationContext,
                    targetPackageName = BAIDU_MAP_PACKAGE_NAME,
                    launchAdjacent = false
                )
            )
            true
        } catch (exception: RuntimeException) {
            Log.w(TAG, "百度地图启动失败", exception)
            launchMapApplication(
                packageName = BAIDU_MAP_PACKAGE_NAME,
                launchAdjacent = false
            )
        }
    }

    // 启动指定地图应用并按需请求相邻分屏窗口。
    private fun launchMapApplication(
        packageName: String,
        launchAdjacent: Boolean
    ): Boolean {
        if (!isMapApplicationInstalled(packageName)) {
            return false
        }
        val intent = if (launchAdjacent) {
            createAdjacentMapLaunchIntent(packageName)
        } else {
            DualMapLaunchActivity.createIntent(
                context = service.applicationContext,
                targetPackageName = packageName,
                launchAdjacent = false
            )
        }
        return try {
            service.startActivity(intent)
            true
        } catch (exception: RuntimeException) {
            Log.w(TAG, "地图应用启动失败：$packageName", exception)
            false
        }
    }

    // 创建直接启动真实地图应用到相邻窗口的 Intent。
    private fun createAdjacentMapLaunchIntent(
        packageName: String
    ): Intent {
        return (
                service.packageManager.getLaunchIntentForPackage(
                    packageName
                ) ?: Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setPackage(packageName)
                }
                ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
            addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
    }

    // 判断目标地图是否具有系统启动入口。
    private fun isMapApplicationInstalled(
        packageName: String
    ): Boolean {
        return service.packageManager
            .getLaunchIntentForPackage(packageName) != null
    }

    private companion object {
        const val TAG = "DualMapSplit"
        const val BAIDU_MAP_PACKAGE_NAME = "com.baidu.BaiduMap"
        const val AMAP_PACKAGE_NAME = "com.autonavi.minimap"
        const val BAIDU_VERIFY_DELAY_MILLIS = 1_000L
        const val BAIDU_VERIFY_MAX_ATTEMPTS = 12
        const val AMAP_LAUNCH_AFTER_SPLIT_DELAY_MILLIS = 2_500L
        const val AMAP_START_RETRY_DELAY_MILLIS = 1_500L
        const val AMAP_START_MAX_ATTEMPTS = 3
        const val DUAL_WINDOW_VERIFY_DELAY_MILLIS = 1_500L
        const val DUAL_WINDOW_VERIFY_MAX_ATTEMPTS = 8

        val BAIDU_RETRY_ATTEMPTS = setOf(3, 6, 9)
        val AMAP_RETRY_ATTEMPTS = setOf(2, 4, 6)
    }
}
