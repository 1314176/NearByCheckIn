// @Time: 2026/07/20 12:00
// @Author: MrWu
// @File: DualMapStateMachineTest.kt
// @Software: Codex

package com.wu.nearbycheckin.domain.statemachine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

import com.wu.nearbycheckin.domain.model.DualMapState
import com.wu.nearbycheckin.domain.model.DualMapTransactionStatus
import com.wu.nearbycheckin.domain.model.MapOperationResult
import com.wu.nearbycheckin.domain.model.MapOperationStatus
import com.wu.nearbycheckin.domain.model.MapProvider

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

    // 验证标准路径严格按照百度后高德的顺序执行。
    @Test
    fun completesBaiduThenAmap() {
        val started = stateMachine.reduce(
            state = DualMapState(
                status = DualMapTransactionStatus.QUEUED
            ),
            event = DualMapEvent.ExecutionStarted(1_000L)
        )
        assertEquals(
            DualMapTransactionStatus.BAIDU_RUNNING,
            started.newState.status
        )
        assertEquals(
            MapProvider.BAIDU,
            started.executeCommand().provider
        )

        val baiduCompleted = stateMachine.reduce(
            state = started.newState,
            event = DualMapEvent.MapResultReceived(
                result = result(
                    provider = MapProvider.BAIDU,
                    status = MapOperationStatus.SUCCESS
                ),
                nowMillis = 2_000L
            )
        )
        assertEquals(
            DualMapTransactionStatus.AMAP_RUNNING,
            baiduCompleted.newState.status
        )
        assertEquals(
            MapProvider.AMAP,
            baiduCompleted.executeCommand().provider
        )

        val amapCompleted = stateMachine.reduce(
            state = baiduCompleted.newState,
            event = DualMapEvent.MapResultReceived(
                result = result(
                    provider = MapProvider.AMAP,
                    status = MapOperationStatus.ALREADY_COMPLETED
                ),
                nowMillis = 3_000L
            )
        )
        assertEquals(
            DualMapTransactionStatus.COMPLETED,
            amapCompleted.newState.status
        )
        assertTrue(
            DomainEffect.MarkPlaceCompleted in amapCompleted.effects
        )
    }

    // 验证一端成功后只重试失败的另一端。
    @Test
    fun retriesOnlyFailedProviderAfterPartialSuccess() {
        val state = DualMapState(
            status = DualMapTransactionStatus.AMAP_RUNNING,
            baiduResult = result(
                provider = MapProvider.BAIDU,
                status = MapOperationStatus.SUCCESS
            ),
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
            DualMapTransactionStatus.PARTIAL_SUCCESS,
            failed.newState.status
        )
        assertTrue(failed.commands.isEmpty())

        val retry = stateMachine.reduce(
            state = failed.newState,
            event = DualMapEvent.RetryDue(15_000L)
        )
        assertEquals(
            DualMapTransactionStatus.AMAP_RUNNING,
            retry.newState.status
        )
        assertEquals(
            MapProvider.AMAP,
            retry.executeCommand().provider
        )
        assertEquals(2, retry.executeCommand().attempt)
    }

    // 验证不匹配当前运行端的重复结果不会改变状态。
    @Test
    fun ignoresDuplicateResultFromUnexpectedProvider() {
        val state = DualMapState(
            status = DualMapTransactionStatus.AMAP_RUNNING,
            baiduResult = result(
                provider = MapProvider.BAIDU,
                status = MapOperationStatus.SUCCESS
            ),
            baiduAttempts = 1,
            amapAttempts = 1
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
