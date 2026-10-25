// @Time: 2026/07/20 18:18
// @Author: MrWu
// @File: StartCruiseUseCase.kt
// @Software: Android Studio


package com.wu.nearbycheckin.core.domain.usecase


import com.wu.nearbycheckin.core.common.time.AppClock
import com.wu.nearbycheckin.core.domain.coordinator.CheckInCoordinator
import com.wu.nearbycheckin.core.domain.gateway.CruisePlatformGateway
import com.wu.nearbycheckin.core.domain.gateway.CruisePrecheck
import com.wu.nearbycheckin.core.domain.repository.CheckInTransactionRepository
import com.wu.nearbycheckin.core.model.AutomationSession


/**
 * 表示启动巡航用例的明确预检或成功结果。
 */
sealed interface StartCruiseResult {

    data object PrivacyConsentRequired : StartCruiseResult

    data object LocationPermissionRequired : StartCruiseResult

    data object OverlayPermissionRequired : StartCruiseResult

    data object AccessibilityServiceRequired : StartCruiseResult

    data class MapsMissing(
        val baiduInstalled: Boolean,
        val amapInstalled: Boolean
    ) : StartCruiseResult

    data object MapSdkInitializationFailed : StartCruiseResult

    data class Started(
        val session: AutomationSession,
        val shouldOpenBaiduForSplit: Boolean
    ) : StartCruiseResult
}

/**
 * 完成启动预检、会话创建、分屏请求和前台服务启动。
 */
class StartCruiseUseCase(
    private val platformGateway: CruisePlatformGateway,
    private val coordinator: CheckInCoordinator,
    private val repository: CheckInTransactionRepository,
    private val clock: AppClock
) {

    // 按固定顺序执行启动预检并返回单一结果。
    operator fun invoke(): StartCruiseResult {
        val precheck = platformGateway.inspectPrecheck()
        precheck.toBlockingResult()?.let {
            return it
        }

        if (!platformGateway.initializeMapSdk()) {
            return StartCruiseResult.MapSdkInitializationFailed
        }

        val diagnostics = repository.currentSnapshot().diagnostics
        val diagnosticsReady = diagnostics.baiduReady &&
                diagnostics.amapReady &&
                clock.nowMillis() -
                diagnostics.lastUpdatedMillis <=
                DIAGNOSTICS_STALE_MILLIS
        if (!diagnosticsReady) {
            platformGateway.requestAutoSplitLaunch()
        }

        val session = coordinator.startSession()
        platformGateway.startCruiseService()
        return StartCruiseResult.Started(
            session = session,
            shouldOpenBaiduForSplit = !diagnosticsReady
        )
    }

    // 返回当前平台预检状态供 ViewModel 生成 UI。
    fun inspectPrecheck(): CruisePrecheck {
        return platformGateway.inspectPrecheck()
    }

    // 保存隐私同意后重新执行完整启动用例。
    fun grantPrivacyConsentAndStart(): StartCruiseResult {
        platformGateway.grantPrivacyConsent()
        return invoke()
    }

    // 清理 Activity 上一次关闭时可能遗留的分屏请求。
    fun initializeMainEntry() {
        platformGateway.clearPendingAutoSplitLaunchRequest()
    }

    // 将第一个未满足的平台前置条件转换为明确结果。
    private fun CruisePrecheck.toBlockingResult(): StartCruiseResult? {
        return when {
            !privacyConsentGranted ->
                StartCruiseResult.PrivacyConsentRequired
            !allMapsInstalled ->
                StartCruiseResult.MapsMissing(
                    baiduInstalled = baiduInstalled,
                    amapInstalled = amapInstalled
                )
            !locationPermissionGranted ->
                StartCruiseResult.LocationPermissionRequired
            !overlayPermissionGranted ->
                StartCruiseResult.OverlayPermissionRequired
            !accessibilityServiceEnabled ->
                StartCruiseResult.AccessibilityServiceRequired
            else -> null
        }
    }

    private companion object {
        const val DIAGNOSTICS_STALE_MILLIS = 45_000L
    }
}
