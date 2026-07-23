// @Time: 2026/07/20 13:00
// @Author: MrWu
// @File: SubmitCandidateUseCase.kt
// @Software: Codex

package com.wu.nearbycheckin.application.usecase

import com.wu.nearbycheckin.domain.coordinator.CandidateSubmissionResult
import com.wu.nearbycheckin.domain.coordinator.CheckInCoordinator
import com.wu.nearbycheckin.domain.model.NearbyPlaceCandidate

/**
 * 为 CruiseService 提供唯一候选提交入口。
 */
class SubmitCandidateUseCase(
    private val coordinator: CheckInCoordinator
) {

    // 将标准化候选交给协调器评估和入队。
    operator fun invoke(
        candidate: NearbyPlaceCandidate
    ): CandidateSubmissionResult {
        return coordinator.submitCandidate(candidate)
    }
}
