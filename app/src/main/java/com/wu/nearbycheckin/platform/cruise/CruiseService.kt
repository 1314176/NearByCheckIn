// @Time: 2026/07/20 18:38
// @Author: MrWu
// @File: CruiseService.kt
// @Software: Android Studio


package com.wu.nearbycheckin.platform.cruise


import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder

import androidx.core.app.NotificationCompat

import com.wu.nearbycheckin.app.NearbyCheckInApplication
import com.wu.nearbycheckin.core.model.AutomationLogLevel
import com.wu.nearbycheckin.core.domain.repository.CheckInTransactionRepository
import com.wu.nearbycheckin.platform.overlay.GlowRadarOverlayController


/**
 * 只负责前台服务生命周期、通知与平台控制器装配。
 */
class CruiseService : Service() {

    private lateinit var discoveryController: CruiseDiscoveryController
    private lateinit var overlayController: GlowRadarOverlayController
    private lateinit var transactionRepository:
            CheckInTransactionRepository
    private var overlayVisible = true
    private var currentNotificationText = DEFAULT_NOTIFICATION_TEXT
    private var foregroundReady = false

    // 创建前台通知并启动定位发现与悬浮窗控制器。
    override fun onCreate() {
        super.onCreate()
        val container = (application as NearbyCheckInApplication).appContainer
        transactionRepository = container.transactionRepository
        foregroundReady = CruiseForegroundStartPolicy.tryStart(
            start = ::startForegroundNotification,
            onRejected = { error ->
                container.checkInCoordinator.stopSession()
                transactionRepository.clearPendingAutoSplitLaunchRequest()
                transactionRepository.updateAutomationStatus(
                    statusText = "巡航启动被系统拒绝，请在主界面检查定位权限后重新启动：${error.message}",
                    source = "巡航服务",
                    level = AutomationLogLevel.ERROR
                )
                stopSelf()
            }
        )
        if (!foregroundReady) return

        overlayController = GlowRadarOverlayController(
            service = this,
            onVisibilityChanged = { visible ->
                overlayVisible = visible
                refreshNotification()
            }
        )
        overlayVisible = overlayController.isVisible
        // 暂时停用“巡航中”悬浮界面，保留控制器代码以便后续恢复。
        // overlayController.restore()

        discoveryController = CruiseDiscoveryController(
            context = applicationContext,
            providerCoordinator = container.providerAutomationCoordinator,
            onStatusChanged = ::updateNotification
        )
        if (!discoveryController.start()) {
            updateNotification("巡航初始化失败，请检查定位和地图配置")
            stopSelf()
        }
    }

    // 处理通知栏中的悬浮雷达显示切换动作。
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        if (!foregroundReady) return START_NOT_STICKY
        if (intent?.action == ACTION_TOGGLE_OVERLAY) {
            // 暂时停用“巡航中”悬浮界面的通知切换入口。
            // overlayController.toggle()
        }
        return START_STICKY
    }

    // 释放定位发现和悬浮窗平台资源。
    override fun onDestroy() {
        if (::discoveryController.isInitialized) {
            discoveryController.destroy()
        }
        if (::overlayController.isInitialized) {
            overlayController.destroy()
        }
        super.onDestroy()
    }

    // 返回空绑定接口，保持纯启动式前台服务。
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    // 创建低打扰通知渠道并进入前台服务状态。
    private fun startForegroundNotification() {
        val notificationManager =
            getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "巡航服务",
                NotificationManager.IMPORTANCE_LOW
            )
        )
        startForeground(
            NOTIFICATION_ID,
            buildNotification(currentNotificationText)
        )
    }

    // 更新前台通知的状态文本。
    private fun updateNotification(contentText: String) {
        currentNotificationText = contentText
        transactionRepository.updateAutomationStatus(
            statusText = contentText,
            source = "巡航服务",
            level = contentText.toLogLevel()
        )
        refreshNotification()
    }

    // 根据状态文本中的失败语义确定日志级别。
    private fun String.toLogLevel(): AutomationLogLevel {
        return when {
            contains("失败") ||
                    contains("错误") ->
                AutomationLogLevel.ERROR
            contains("不可用") ||
                    contains("尚未") ->
                AutomationLogLevel.WARNING
            else -> AutomationLogLevel.INFO
        }
    }

    // 使用当前状态重新发布前台通知。
    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(currentNotificationText)
        )
    }

    // 创建包含悬浮雷达切换动作的前台通知。
    private fun buildNotification(contentText: String): Notification {
        // 暂时停用“巡航中”悬浮界面的通知入口，保留原实现以便后续恢复。
//        val toggleIntent = Intent(
//            this,
//            CruiseService::class.java
//        ).apply {
//            action = ACTION_TOGGLE_OVERLAY
//        }
//        val pendingIntent = PendingIntent.getService(
//            this,
//            TOGGLE_OVERLAY_REQUEST_CODE,
//            toggleIntent,
//            PendingIntent.FLAG_UPDATE_CURRENT or
//                    PendingIntent.FLAG_IMMUTABLE
//        )
        return NotificationCompat.Builder(
            this,
            NOTIFICATION_CHANNEL_ID
        )
            .setContentTitle("微光雷达运行中")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
//            .addAction(
//                android.R.drawable.ic_menu_view,
//                if (overlayVisible) {
//                    "隐藏雷达"
//                } else {
//                    "显示雷达"
//                },
//                pendingIntent
//            )
            .build()
    }

    private companion object {
        const val NOTIFICATION_CHANNEL_ID = "cruise_service_channel"
        const val NOTIFICATION_ID = 101
        const val TOGGLE_OVERLAY_REQUEST_CODE = 102
        const val DEFAULT_NOTIFICATION_TEXT =
            "正在为您自动监测周边打卡点..."
        const val ACTION_TOGGLE_OVERLAY =
            "com.wu.nearbycheckin.action.TOGGLE_OVERLAY"
    }
}

internal object CruiseForegroundStartPolicy {

    // 将权限或后台启动限制转为可恢复的失败，避免定位服务反复崩溃。
    fun tryStart(start: () -> Unit, onRejected: (RuntimeException) -> Unit): Boolean {
        try {
            start()
        } catch (error: SecurityException) {
            onRejected(error)
            return false
        } catch (error: IllegalStateException) {
            onRejected(error)
            return false
        }
        return true
    }
}
