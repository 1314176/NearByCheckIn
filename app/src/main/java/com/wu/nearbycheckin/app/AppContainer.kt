// @Time: 2026/07/20 18:18
// @Author: MrWu
// @File: AppContainer.kt
// @Software: Android Studio


package com.wu.nearbycheckin.app


import android.content.Context

import com.wu.nearbycheckin.core.common.time.SystemAppClock
import com.wu.nearbycheckin.core.domain.coordinator.CheckInCoordinator
import com.wu.nearbycheckin.core.domain.coordinator.ProviderAutomationCoordinator
import com.wu.nearbycheckin.core.domain.evaluator.CandidateEvaluator
import com.wu.nearbycheckin.core.domain.repository.CheckInTransactionRepository
import com.wu.nearbycheckin.core.domain.plugin.MapAutomationPluginEntry
import com.wu.nearbycheckin.core.domain.plugin.MapAutomationPluginRegistry
import com.wu.nearbycheckin.core.domain.plugin.PluginAvailability
import com.wu.nearbycheckin.core.domain.statemachine.DualMapStateMachine
import com.wu.nearbycheckin.core.domain.usecase.ClearRecordsUseCase
import com.wu.nearbycheckin.core.domain.usecase.QueryRecordsUseCase
import com.wu.nearbycheckin.core.domain.usecase.StartCruiseUseCase
import com.wu.nearbycheckin.core.domain.usecase.StopCruiseUseCase
import com.wu.nearbycheckin.core.domain.usecase.SubmitCandidateUseCase
import com.wu.nearbycheckin.data.repository.LegacyCheckInDataMigrator
import com.wu.nearbycheckin.data.repository.SharedPreferencesCheckInTransactionRepository
import com.wu.nearbycheckin.platform.cruise.AndroidCruisePlatformGateway
import com.wu.nearbycheckin.platform.maps.AmapCheckInPlugin
import com.wu.nearbycheckin.platform.maps.BaiduCheckInPlugin
import com.wu.nearbycheckin.core.model.MapProvider


/**
 * 集中创建并共享 V4 架构的 Repository、协调器和用例。
 */
class AppContainer(
    context: Context
) {

    private val appClock = SystemAppClock
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
        clock = appClock
    )
    val providerAutomationCoordinator = ProviderAutomationCoordinator(
        repository = transactionRepository,
        clock = appClock
    )
    val mapAutomationPluginRegistry = MapAutomationPluginRegistry(
        listOf(
            MapAutomationPluginEntry(
                provider = MapProvider.BAIDU,
                availability = PluginAvailability.ENABLED,
                plugin = BaiduCheckInPlugin()
            ),
            MapAutomationPluginEntry(
                provider = MapProvider.AMAP,
                availability = PluginAvailability.ENABLED,
                plugin = AmapCheckInPlugin()
            )
        )
    )
    val startCruiseUseCase = StartCruiseUseCase(
        platformGateway = platformGateway,
        coordinator = checkInCoordinator,
        repository = transactionRepository,
        clock = appClock
    )
    val stopCruiseUseCase = StopCruiseUseCase(
        platformGateway = platformGateway,
        coordinator = checkInCoordinator
    )
    val submitCandidateUseCase = SubmitCandidateUseCase(
        coordinator = checkInCoordinator
    )
    val queryRecordsUseCase = QueryRecordsUseCase(
        repository = transactionRepository,
        clock = appClock
    )
    val clearRecordsUseCase = ClearRecordsUseCase(
        repository = transactionRepository,
        legacyDataCleaner = legacyMigrator
    )
}
