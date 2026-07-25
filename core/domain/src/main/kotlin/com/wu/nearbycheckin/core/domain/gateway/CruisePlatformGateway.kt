// @Time: 2026/07/20 18:18
// @Author: MrWu
// @File: CruisePlatformGateway.kt
// @Software: Android Studio


package com.wu.nearbycheckin.core.domain.gateway


/**
 * 保存启动巡航前需要检查的平台能力状态。
 */
data class CruisePrecheck(
    val privacyConsentGranted: Boolean,
    val locationPermissionGranted: Boolean,
    val notificationPermissionGranted: Boolean,
    val overlayPermissionGranted: Boolean,
    val accessibilityServiceEnabled: Boolean,
    val baiduInstalled: Boolean,
    val amapInstalled: Boolean
) {

    val allMapsInstalled: Boolean
        get() = baiduInstalled && amapInstalled
}

/**
 * 隔离用例层与 Android Service、权限和 SharedPreferences。
 */
interface CruisePlatformGateway {

    // 读取当前巡航启动所需的平台预检状态。
    fun inspectPrecheck(): CruisePrecheck

    // 持久化用户对百度地图 SDK 的隐私授权。
    fun grantPrivacyConsent()

    // 初始化百度地图 SDK 的隐私与鉴权入口。
    fun initializeMapSdk(): Boolean

    // 启动只负责定位、POI 和悬浮窗的平台服务。
    fun startCruiseService()

    // 停止巡航平台服务。
    fun stopCruiseService()

    // 请求无障碍平台适配器建立双地图分屏。
    fun requestAutoSplitLaunch()

    // 清除上次页面会话遗留的分屏请求。
    fun clearPendingAutoSplitLaunchRequest()
}
