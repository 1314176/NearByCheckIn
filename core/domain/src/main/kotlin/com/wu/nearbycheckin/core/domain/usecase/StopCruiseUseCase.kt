// @Time: 2026/07/20 18:18
// @Author: MrWu
// @File: StopCruiseUseCase.kt
// @Software: Android Studio


package com.wu.nearbycheckin.core.domain.usecase


import com.wu.nearbycheckin.core.domain.coordinator.CheckInCoordinator
import com.wu.nearbycheckin.core.domain.gateway.CruisePlatformGateway


/**
 * 停止平台巡航并让领域事务收敛到取消状态。
 */
class StopCruiseUseCase(
    private val platformGateway: CruisePlatformGateway,
    private val coordinator: CheckInCoordinator
) {

    // 按领域状态后平台资源的顺序停止巡航。
    operator fun invoke() {
        coordinator.stopSession()
        platformGateway.stopCruiseService()
    }
}
