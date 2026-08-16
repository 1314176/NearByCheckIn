// @Time: 2026/07/20 18:45
// @Author: MrWu
// @File: CheckInCoordinatorTest.kt
// @Software: Android Studio


package com.wu.nearbycheckin.core.domain.coordinator


import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

import com.wu.nearbycheckin.core.common.time.AppClock
import com.wu.nearbycheckin.core.domain.evaluator.CandidateEvaluationPolicy
import com.wu.nearbycheckin.core.domain.evaluator.CandidateEvaluator
import com.wu.nearbycheckin.core.domain.statemachine.DualMapStateMachine
import com.wu.nearbycheckin.core.domain.statemachine.DualMapStateMachinePolicy
import com.wu.nearbycheckin.core.domain.testing.TestCheckInTransactionRepository
import com.wu.nearbycheckin.core.model.DualMapTransactionStatus
import com.wu.nearbycheckin.core.model.MapCommand
import com.wu.nearbycheckin.core.model.MapOperationResult
import com.wu.nearbycheckin.core.model.MapOperationStatus
import com.wu.nearbycheckin.core.model.MapProvider
import com.wu.nearbycheckin.core.model.NearbyPlaceCandidate


/**
 * 验证协调器独立 Provider 队列、真实位置去重和永久完成写入。
 */
class CheckInCoordinatorTest {

    // 验证高德失败后只生成高德重试并最终完成事务。
    @Test
    fun retriesOnlyAmapAfterBaiduSuccess() {
        val repository = TestCheckInTransactionRepository()
        val clock = MutableAppClock(nowMillis = 10_000L)
        val coordinator = CheckInCoordinator(
            repository = repository,
            candidateEvaluator = CandidateEvaluator(
                CandidateEvaluationPolicy(
                    requiredStayMillis = 0L,
                    fastRequiredStayMillis = 0L
                )
            ),
            stateMachine = DualMapStateMachine(
                DualMapStateMachinePolicy(
                    maxAttemptsPerProvider = 3,
                    retryDelayMillis = 5_000L
                )
            ),
            clock = clock
        )

        coordinator.startSession()
        coordinator.submitCandidate(candidate(clock.nowMillis))
        val baiduCommand = checkNotNull(
            coordinator.claimNextCommand(
                provider = MapProvider.BAIDU,
                workerId = "baidu-worker"
            )
        )
        assertEquals(MapProvider.BAIDU, baiduCommand.provider)
        assertTrue(
            coordinator.completeCommand(
                baiduCommand.commandId,
                result(
                    command = baiduCommand,
                    status = MapOperationStatus.SUCCESS
                )
            )
        )

        val amapCommand = checkNotNull(
            coordinator.claimNextCommand(
                provider = MapProvider.AMAP,
                workerId = "amap-worker"
            )
        )
        assertEquals(MapProvider.AMAP, amapCommand.provider)
        assertTrue(
            coordinator.completeCommand(
                amapCommand.commandId,
                result(
                    command = amapCommand,
                    status = MapOperationStatus.FAILED
                )
            )
        )
        assertEquals(
            DualMapTransactionStatus.PARTIAL_SUCCESS,
            repository.currentSnapshot()
                .activeTransaction
                ?.state
                ?.status
        )
        assertFalse(
            coordinator.completeCommand(
                amapCommand.commandId,
                result(
                    command = amapCommand,
                    status = MapOperationStatus.FAILED
                )
            )
        )

        clock.nowMillis += 5_000L
        val retryCommand = checkNotNull(
            coordinator.claimNextCommand(
                provider = MapProvider.AMAP,
                workerId = "amap-worker"
            )
        )
        assertEquals(MapProvider.AMAP, retryCommand.provider)
        assertEquals(2, retryCommand.attempt)
        coordinator.completeCommand(
            retryCommand.commandId,
            result(
                command = retryCommand,
                status = MapOperationStatus.ALREADY_COMPLETED
            )
        )

        val snapshot = repository.currentSnapshot()
        assertEquals(
            DualMapTransactionStatus.COMPLETED,
            snapshot.transactions.single().state.status
        )
        assertTrue("poi-1" in snapshot.completedPlaceKeys)
        assertEquals(1, snapshot.records.size)
    }

    // 验证高德重试期间百度 Worker 仍可领取下一地点命令。
    @Test
    fun baiduQueueContinuesWhileAmapIsRetrying() {
        val fixture = coordinatorFixture()
        fixture.coordinator.startSession()
        fixture.coordinator.submitCandidate(
            candidate(
                nowMillis = fixture.clock.nowMillis,
                id = "poi-1",
                deviceLatitude = 31.0
            )
        )
        fixture.clock.nowMillis += 1L
        fixture.coordinator.submitCandidate(
            candidate(
                nowMillis = fixture.clock.nowMillis,
                id = "poi-2",
                deviceLatitude = 31.002
            )
        )

        val firstBaidu = checkNotNull(
            fixture.coordinator.claimNextCommand(
                provider = MapProvider.BAIDU,
                workerId = "baidu-worker"
            )
        )
        fixture.coordinator.completeCommand(
            firstBaidu.commandId,
            result(firstBaidu, MapOperationStatus.SUCCESS)
        )
        val firstAmap = checkNotNull(
            fixture.coordinator.claimNextCommand(
                provider = MapProvider.AMAP,
                workerId = "amap-worker"
            )
        )
        fixture.coordinator.completeCommand(
            firstAmap.commandId,
            result(firstAmap, MapOperationStatus.FAILED)
        )

        val secondBaidu = checkNotNull(
            fixture.coordinator.claimNextCommand(
                provider = MapProvider.BAIDU,
                workerId = "baidu-worker"
            )
        )
        assertEquals(
            "poi-2",
            fixture.repository.currentSnapshot().transactions
                .first { transaction ->
                    transaction.transactionId ==
                            secondBaidu.transactionId
                }
                .candidate
                .unifiedPlaceId
        )
    }

    // 验证同一设备真实位置只执行领导事务并复用高德成功结果。
    @Test
    fun reusesAmapResultForSameRealLocation() {
        val fixture = coordinatorFixture()
        fixture.coordinator.startSession()
        fixture.coordinator.submitCandidate(
            candidate(
                nowMillis = fixture.clock.nowMillis,
                id = "poi-1",
                deviceLatitude = 31.0
            )
        )
        fixture.clock.nowMillis += 1L
        fixture.coordinator.submitCandidate(
            candidate(
                nowMillis = fixture.clock.nowMillis,
                id = "poi-2",
                deviceLatitude = 31.0001
            )
        )

        val leaderCommand = checkNotNull(
            fixture.coordinator.claimNextCommand(
                provider = MapProvider.AMAP,
                workerId = "amap-worker"
            )
        )
        fixture.coordinator.completeCommand(
            leaderCommand.commandId,
            result(leaderCommand, MapOperationStatus.SUCCESS)
        )

        val transactions = fixture.repository.currentSnapshot()
            .transactions
            .sortedBy { transaction -> transaction.createdAtMillis }
        assertTrue(transactions.first().state.amapTerminal)
        assertTrue(transactions.last().state.amapTerminal)
        assertEquals(
            MapOperationStatus.ALREADY_COMPLETED,
            transactions.last().state.amapResult?.status
        )
        assertEquals(
            null,
            fixture.coordinator.claimNextCommand(
                provider = MapProvider.AMAP,
                workerId = "amap-worker"
            )
        )
    }

    // 创建可靠且无需等待停留的测试候选。
    private fun candidate(
        nowMillis: Long,
        id: String = "poi-1",
        deviceLatitude: Double = 31.0,
        deviceLongitude: Double = 121.0
    ): NearbyPlaceCandidate {
        return NearbyPlaceCandidate(
            unifiedPlaceId = id,
            providerPlaceIds = setOf(id),
            name = "测试地点",
            address = "测试地址",
            category = "测试分类",
            latitude = 31.0,
            longitude = 121.0,
            distanceMeters = 10,
            locationAccuracyMeters = 5F,
            speedKmh = 0F,
            locationTimeMillis = nowMillis,
            firstSeenAtMillis = nowMillis,
            observedAtMillis = nowMillis,
            deviceLatitude = deviceLatitude,
            deviceLongitude = deviceLongitude
        )
    }

    // 创建包含可推进时钟和内存仓库的协调器测试夹具。
    private fun coordinatorFixture(): CoordinatorFixture {
        val repository = TestCheckInTransactionRepository()
        val clock = MutableAppClock(nowMillis = 10_000L)
        return CoordinatorFixture(
            repository = repository,
            clock = clock,
            coordinator = CheckInCoordinator(
                repository = repository,
                candidateEvaluator = CandidateEvaluator(
                    CandidateEvaluationPolicy(
                        requiredStayMillis = 0L,
                        fastRequiredStayMillis = 0L
                    )
                ),
                stateMachine = DualMapStateMachine(
                    DualMapStateMachinePolicy(
                        maxAttemptsPerProvider = 3,
                        retryDelayMillis = 5_000L
                    )
                ),
                clock = clock
            )
        )
    }

    // 创建与持久化命令关联的地图结果。
    private fun result(
        command: MapCommand,
        status: MapOperationStatus
    ): MapOperationResult {
        return MapOperationResult(
            provider = command.provider,
            flowVersion = "test",
            status = status,
            startedAt = 1L,
            completedAt = 2L,
            attempts = command.attempt,
            matchedPlaceText = "",
            matchedNode = "",
            failureCode = "",
            failureMessage = "",
            evidenceText = "",
            commandId = command.commandId,
            transactionId = command.transactionId
        )
    }
}

/**
 * 聚合协调器测试共享依赖。
 */
private data class CoordinatorFixture(
    val repository: TestCheckInTransactionRepository,
    val clock: MutableAppClock,
    val coordinator: CheckInCoordinator
)

/**
 * 为测试提供可推进的显式时间。
 */
private class MutableAppClock(
    var nowMillis: Long
) : AppClock {

    // 返回测试控制的当前时间。
    override fun nowMillis(): Long {
        return nowMillis
    }
}
