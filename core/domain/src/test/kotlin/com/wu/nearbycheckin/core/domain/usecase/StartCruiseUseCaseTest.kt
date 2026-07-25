// @Time: 2026/07/26 02:49
// @Author: MrWu
// @File: StartCruiseUseCaseTest.kt
// @Software: Android Studio


package com.wu.nearbycheckin.core.domain.usecase


import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

import com.wu.nearbycheckin.core.common.time.AppClock
import com.wu.nearbycheckin.core.domain.coordinator.CheckInCoordinator
import com.wu.nearbycheckin.core.domain.evaluator.CandidateEvaluator
import com.wu.nearbycheckin.core.domain.statemachine.DualMapStateMachine
import com.wu.nearbycheckin.core.domain.testing.FakeCruisePlatformGateway
import com.wu.nearbycheckin.core.domain.testing.TestCheckInTransactionRepository
import com.wu.nearbycheckin.core.model.AccessibilityDiagnostics
import com.wu.nearbycheckin.core.model.AutomationSnapshot


class StartCruiseUseCaseTest {

    // 验证诊断新鲜时不会重复请求分屏。
    @Test
    fun doesNotRequestSplitWhenDiagnosticsAreFresh() {
        val nowMillis = 100_000L
        val repository = TestCheckInTransactionRepository(
            AutomationSnapshot(
                diagnostics = AccessibilityDiagnostics(
                    baiduReady = true,
                    amapReady = true,
                    lastUpdatedMillis = 99_000L
                )
            )
        )
        val platform = FakeCruisePlatformGateway()
        val clock = AppClock { nowMillis }
        val coordinator = CheckInCoordinator(
            repository = repository,
            candidateEvaluator = CandidateEvaluator(),
            stateMachine = DualMapStateMachine(),
            clock = clock
        )
        val useCase = StartCruiseUseCase(
            platformGateway = platform,
            coordinator = coordinator,
            repository = repository,
            clock = clock
        )

        val result = useCase()

        assertTrue(result is StartCruiseResult.Started)
        assertFalse(platform.autoSplitRequested)
        assertTrue(platform.serviceStarted)
    }
}
