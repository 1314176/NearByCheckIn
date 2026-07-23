// @Time: 2026/07/20 13:00
// @Author: MrWu
// @File: AppContainer.kt
// @Software: Codex

package com.wu.nearbycheckin.app

import android.content.Context

import com.wu.nearbycheckin.application.usecase.ClearRecordsUseCase
import com.wu.nearbycheckin.application.usecase.QueryRecordsUseCase
import com.wu.nearbycheckin.application.usecase.StartCruiseUseCase
import com.wu.nearbycheckin.application.usecase.StopCruiseUseCase
import com.wu.nearbycheckin.application.usecase.SubmitCandidateUseCase
import com.wu.nearbycheckin.data.repository.LegacyCheckInDataMigrator
import com.wu.nearbycheckin.data.repository.SharedPreferencesCheckInTransactionRepository
import com.wu.nearbycheckin.domain.coordinator.AutomationClock
import com.wu.nearbycheckin.domain.coordinator.CheckInCoordinator
import com.wu.nearbycheckin.domain.evaluator.CandidateEvaluator
import com.wu.nearbycheckin.domain.repository.CheckInTransactionRepository
import com.wu.nearbycheckin.domain.statemachine.DualMapStateMachine
import com.wu.nearbycheckin.platform.cruise.AndroidCruisePlatformGateway

/**
 * 集中创建并共享 V4 架构的 Repository、协调器和用例。
 */
class AppContainer(
    context: Context
) {

    private val legacyMigrator = LegacyCheckInDataMigrator(context)
    val transactionRepository: CheckInTransactionRepository =
        SharedPreferencesCheckInTransactionRepository(
            context = context,
            legacyMigrator = legacyMigrator
        )
    private val platformGateway = AndroidCruisePlatformGateway(
        context = context,
        transactionRepository = transactionRepository
    )
    val checkInCoordinator = CheckInCoordinator(
        repository = transactionRepository,
        candidateEvaluator = CandidateEvaluator(),
        stateMachine = DualMapStateMachine(),
        clock = AutomationClock(System::currentTimeMillis)
    )
    val startCruiseUseCase = StartCruiseUseCase(
        platformGateway = platformGateway,
        coordinator = checkInCoordinator,
        repository = transactionRepository
    )
    val stopCruiseUseCase = StopCruiseUseCase(
        platformGateway = platformGateway,
        coordinator = checkInCoordinator
    )
    val submitCandidateUseCase = SubmitCandidateUseCase(
        coordinator = checkInCoordinator
    )
    val queryRecordsUseCase = QueryRecordsUseCase(
        repository = transactionRepository
    )
    val clearRecordsUseCase = ClearRecordsUseCase(
        repository = transactionRepository,
        legacyMigrator = legacyMigrator
    )
}
