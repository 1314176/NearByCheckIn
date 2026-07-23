// @Time: 2026/07/20 13:00
// @Author: MrWu
// @File: StopCruiseUseCase.kt
// @Software: Codex

package com.wu.nearbycheckin.application.usecase

import com.wu.nearbycheckin.application.gateway.CruisePlatformGateway
import com.wu.nearbycheckin.domain.coordinator.CheckInCoordinator

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
