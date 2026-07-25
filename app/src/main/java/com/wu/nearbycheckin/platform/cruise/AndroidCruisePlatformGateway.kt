// @Time: 2026/07/20 18:18
// @Author: MrWu
// @File: AndroidCruisePlatformGateway.kt
// @Software: Android Studio


package com.wu.nearbycheckin.platform.cruise


import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

import androidx.core.content.ContextCompat
import androidx.core.content.edit

import com.wu.nearbycheckin.core.domain.gateway.CruisePlatformGateway
import com.wu.nearbycheckin.core.domain.gateway.CruisePrecheck
import com.wu.nearbycheckin.core.domain.repository.CheckInTransactionRepository
import com.wu.nearbycheckin.platform.accessibility.DualMapAccessibilityService


/**
 * 使用 Android 系统能力实现巡航用例的平台端口。
 */
class AndroidCruisePlatformGateway(
    context: Context,
    private val transactionRepository: CheckInTransactionRepository
) : CruisePlatformGateway {

    private val applicationContext = context.applicationContext
    private val privacyPreferences =
        applicationContext.getSharedPreferences(
            PRIVACY_PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )

    // 汇总权限、地图安装和无障碍服务状态。
    override fun inspectPrecheck(): CruisePrecheck {
        return CruisePrecheck(
            privacyConsentGranted = privacyPreferences.getBoolean(
                BAIDU_PRIVACY_CONSENT_KEY,
                false
            ),
            locationPermissionGranted =
                hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ||
                        hasPermission(
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ),
            notificationPermissionGranted =
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        hasPermission(
                            Manifest.permission.POST_NOTIFICATIONS
                        ),
            overlayPermissionGranted =
                Settings.canDrawOverlays(applicationContext),
            accessibilityServiceEnabled =
                isAccessibilityServiceEnabled(),
            baiduInstalled = isPackageInstalled(BAIDU_MAP_PACKAGE_NAME),
            amapInstalled = isPackageInstalled(AMAP_PACKAGE_NAME)
        )
    }

    // 保存百度地图 SDK 隐私授权。
    override fun grantPrivacyConsent() {
        privacyPreferences.edit {
            putBoolean(BAIDU_PRIVACY_CONSENT_KEY, true)
        }
    }

    // 初始化百度地图 SDK。
    override fun initializeMapSdk(): Boolean {
        return BaiduMapSdkManager.initializeAfterPrivacyConsent(
            applicationContext
        )
    }

    // 启动前台定位与 POI 平台服务。
    override fun startCruiseService() {
        ContextCompat.startForegroundService(
            applicationContext,
            Intent(applicationContext, CruiseService::class.java)
        )
    }

    // 停止前台定位与 POI 平台服务。
    override fun stopCruiseService() {
        applicationContext.stopService(
            Intent(applicationContext, CruiseService::class.java)
        )
    }

    // 写入一次可由无障碍平台适配器消费的分屏请求。
    override fun requestAutoSplitLaunch() {
        transactionRepository.requestAutoSplitLaunch(
            System.currentTimeMillis()
        )
    }

    // 清除遗留的分屏请求。
    override fun clearPendingAutoSplitLaunchRequest() {
        transactionRepository.clearPendingAutoSplitLaunchRequest()
    }

    // 判断应用是否已经获得指定运行时权限。
    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            applicationContext,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    // 判断目标地图应用是否已经安装。
    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            @Suppress("DEPRECATION")
            applicationContext.packageManager.getPackageInfo(
                packageName,
                0
            )
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    // 判断项目无障碍平台适配器是否已由用户启用。
    private fun isAccessibilityServiceEnabled(): Boolean {
        val targetComponent = ComponentName(
            applicationContext,
            DualMapAccessibilityService::class.java
        )
        val accessibilityManager =
            applicationContext.getSystemService(
                Context.ACCESSIBILITY_SERVICE
            ) as AccessibilityManager
        val enabledByManager = accessibilityManager
            .getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            )
            .any { serviceInfo ->
                val resolvedService = serviceInfo.resolveInfo.serviceInfo
                ComponentName(
                    resolvedService.packageName,
                    resolvedService.name
                ) == targetComponent
            }
        if (enabledByManager) {
            return true
        }

        val enabledServices = Settings.Secure.getString(
            applicationContext.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabledServices.split(':').any { flattenedComponent ->
            ComponentName.unflattenFromString(flattenedComponent) ==
                    targetComponent
        }
    }

    private companion object {
        const val PRIVACY_PREFERENCES_NAME = "privacy_preferences"
        const val BAIDU_PRIVACY_CONSENT_KEY =
            "baidu_map_privacy_consent"
        const val BAIDU_MAP_PACKAGE_NAME = "com.baidu.BaiduMap"
        const val AMAP_PACKAGE_NAME = "com.autonavi.minimap"
    }
}
