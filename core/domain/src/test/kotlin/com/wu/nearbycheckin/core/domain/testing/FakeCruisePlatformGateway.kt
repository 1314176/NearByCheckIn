// @Time: 2026/07/26 02:49
// @Author: MrWu
// @File: FakeCruisePlatformGateway.kt
// @Software: Android Studio


package com.wu.nearbycheckin.core.domain.testing


import com.wu.nearbycheckin.core.domain.gateway.CruisePlatformGateway
import com.wu.nearbycheckin.core.domain.gateway.CruisePrecheck


internal class FakeCruisePlatformGateway(
    var precheck: CruisePrecheck = CruisePrecheck(
        privacyConsentGranted = true,
        locationPermissionGranted = true,
        notificationPermissionGranted = true,
        overlayPermissionGranted = true,
        accessibilityServiceEnabled = true,
        baiduInstalled = true,
        amapInstalled = true
    ),
    var mapSdkInitialized: Boolean = true
) : CruisePlatformGateway {

    var serviceStarted = false
    var serviceStopped = false
    var autoSplitRequested = false

    // 返回测试配置的平台预检。
    override fun inspectPrecheck(): CruisePrecheck = precheck

    // 记录并授予测试隐私授权。
    override fun grantPrivacyConsent() {
        precheck = precheck.copy(privacyConsentGranted = true)
    }

    // 返回测试配置的 SDK 初始化结果。
    override fun initializeMapSdk(): Boolean = mapSdkInitialized

    // 记录巡航服务启动。
    override fun startCruiseService() {
        serviceStarted = true
    }

    // 记录巡航服务停止。
    override fun stopCruiseService() {
        serviceStopped = true
    }

    // 记录自动分屏请求。
    override fun requestAutoSplitLaunch() {
        autoSplitRequested = true
    }

    // 清理测试中不需要持久化的历史分屏请求。
    override fun clearPendingAutoSplitLaunchRequest() = Unit
}
