// @Time: 2026/07/20 18:03
// @Author: MrWu
// @File: DualMapStateMachineTest.kt
// @Software: Android Studio


package com.wu.nearbycheckin.core.domain.statemachine


import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

import com.wu.nearbycheckin.core.model.DualMapState
import com.wu.nearbycheckin.core.model.DualMapTransactionStatus
import com.wu.nearbycheckin.core.model.MapOperationResult
import com.wu.nearbycheckin.core.model.MapOperationStatus
import com.wu.nearbycheckin.core.model.MapProvider


/**
 * 验证双地图 reducer 的标准路径、部分失败与幂等行为。
 */
class DualMapStateMachineTest {

    private val stateMachine = DualMapStateMachine(
        DualMapStateMachinePolicy(
            maxAttemptsPerProvider = 3,
            retryDelayMillis = 5_000L
        )
    )

    // 验证启动时同时生成百度和高德命令并允许结果乱序回写。
    @Test
    fun startsBothProvidersAndAcceptsOutOfOrderResults() {
        val started = stateMachine.reduce(
            state = DualMapState(
                status = DualMapTransactionStatus.QUEUED
            ),
            event = DualMapEvent.ExecutionStarted(1_000L)
        )
        assertEquals(
            DualMapTransactionStatus.RUNNING,
            started.newState.status
        )
        assertEquals(
            setOf(MapProvider.BAIDU, MapProvider.AMAP),
            started.commands.map { command ->
                (command as DualMapCommand.ExecuteMapOperation).provider
            }.toSet()
        )

        val amapCompleted = stateMachine.reduce(
            state = started.newState,
            event = DualMapEvent.MapResultReceived(
                result = result(
                    provider = MapProvider.AMAP,
                    status = MapOperationStatus.SUCCESS
                ),
                nowMillis = 2_000L
            )
        )
        assertEquals(
            DualMapTransactionStatus.PARTIAL_SUCCESS,
            amapCompleted.newState.status
        )
        assertTrue(amapCompleted.commands.isEmpty())

        val baiduCompleted = stateMachine.reduce(
            state = amapCompleted.newState,
            event = DualMapEvent.MapResultReceived(
                result = result(
                    provider = MapProvider.BAIDU,
                    status = MapOperationStatus.ALREADY_COMPLETED
                ),
                nowMillis = 3_000L
            )
        )
        assertEquals(
            DualMapTransactionStatus.COMPLETED,
            baiduCompleted.newState.status
        )
        assertTrue(
            DomainEffect.MarkPlaceCompleted in baiduCompleted.effects
        )
    }

    // 验证高德失败会独立生成延迟重试且不等待百度结果。
    @Test
    fun schedulesOnlyFailedProviderRetryImmediately() {
        val state = DualMapState(
            status = DualMapTransactionStatus.RUNNING,
            baiduAttempts = 1,
            amapAttempts = 1
        )
        val failed = stateMachine.reduce(
            state = state,
            event = DualMapEvent.MapResultReceived(
                result = result(
                    provider = MapProvider.AMAP,
                    status = MapOperationStatus.FAILED
                ),
                nowMillis = 10_000L
            )
        )
        assertEquals(
            DualMapTransactionStatus.RUNNING,
            failed.newState.status
        )
        assertEquals(
            MapProvider.AMAP,
            failed.executeCommand().provider
        )
        assertEquals(2, failed.executeCommand().attempt)
        assertEquals(15_000L, failed.executeCommand().availableAtMillis)
    }

    // 验证已经形成终态的 Provider 重复结果不会改变状态。
    @Test
    fun ignoresDuplicateResultFromTerminalProvider() {
        val state = DualMapState(
            status = DualMapTransactionStatus.PARTIAL_SUCCESS,
            baiduResult = result(
                provider = MapProvider.BAIDU,
                status = MapOperationStatus.SUCCESS
            ),
            baiduAttempts = 1,
            amapAttempts = 1,
            baiduTerminal = true
        )

        val transition = stateMachine.reduce(
            state = state,
            event = DualMapEvent.MapResultReceived(
                result = result(
                    provider = MapProvider.BAIDU,
                    status = MapOperationStatus.SUCCESS
                ),
                nowMillis = 20_000L
            )
        )

        assertEquals(state, transition.newState)
        assertTrue(transition.commands.isEmpty())
    }

    // 创建指定地图端和状态的测试结果。
    private fun result(
        provider: MapProvider,
        status: MapOperationStatus
    ): MapOperationResult {
        return MapOperationResult(
            provider = provider,
            flowVersion = "test",
            status = status,
            startedAt = 1L,
            completedAt = 2L,
            attempts = 1,
            matchedPlaceText = "",
            matchedNode = "",
            failureCode = "",
            failureMessage = "",
            evidenceText = ""
        )
    }

    // 返回一次状态机转换中唯一的地图执行命令。
    private fun DualMapTransition.executeCommand():
            DualMapCommand.ExecuteMapOperation {
        return commands.single() as DualMapCommand.ExecuteMapOperation
    }
}
