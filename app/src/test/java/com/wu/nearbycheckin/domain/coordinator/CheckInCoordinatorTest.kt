// @Time: 2026/07/20 16:30
// @Author: MrWu
// @File: CheckInCoordinatorTest.kt
// @Software: Codex

package com.wu.nearbycheckin.domain.coordinator

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

import com.wu.nearbycheckin.domain.evaluator.CandidateEvaluationPolicy
import com.wu.nearbycheckin.domain.evaluator.CandidateEvaluator
import com.wu.nearbycheckin.domain.model.AccessibilityDiagnostics
import com.wu.nearbycheckin.domain.model.AutomationLogEntry
import com.wu.nearbycheckin.domain.model.AutomationLogLevel
import com.wu.nearbycheckin.domain.model.AutomationSnapshot
import com.wu.nearbycheckin.domain.model.DualMapTransactionStatus
import com.wu.nearbycheckin.domain.model.MapCommand
import com.wu.nearbycheckin.domain.model.MapCommandStatus
import com.wu.nearbycheckin.domain.model.MapOperationResult
import com.wu.nearbycheckin.domain.model.MapOperationStatus
import com.wu.nearbycheckin.domain.model.MapProvider
import com.wu.nearbycheckin.domain.model.NearbyPlaceCandidate
import com.wu.nearbycheckin.domain.repository.CheckInTransactionRepository
import com.wu.nearbycheckin.domain.statemachine.DualMapStateMachine
import com.wu.nearbycheckin.domain.statemachine.DualMapStateMachinePolicy

/**
 * 验证协调器串行命令、部分成功重试和永久完成写入。
 */
class CheckInCoordinatorTest {

    // 验证高德失败后只生成高德重试并最终完成事务。
    @Test
    fun retriesOnlyAmapAfterBaiduSuccess() {
        val repository = FakeTransactionRepository()
        val clock = MutableAutomationClock(nowMillis = 10_000L)
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
            coordinator.claimNextCommand("worker")
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
            coordinator.claimNextCommand("worker")
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
            coordinator.claimNextCommand("worker")
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

    // 创建可靠且无需等待停留的测试候选。
    private fun candidate(nowMillis: Long): NearbyPlaceCandidate {
        return NearbyPlaceCandidate(
            unifiedPlaceId = "poi-1",
            providerPlaceIds = setOf("poi-1"),
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
            observedAtMillis = nowMillis
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
 * 为测试提供可推进的显式时间。
 */
private class MutableAutomationClock(
    var nowMillis: Long
) : AutomationClock {

    // 返回测试控制的当前时间。
    override fun nowMillis(): Long {
        return nowMillis
    }
}

/**
 * 在 JVM 内存中实现 Repository 原子契约。
 */
private class FakeTransactionRepository :
    CheckInTransactionRepository {

    private val mutableSnapshot = MutableStateFlow(
        AutomationSnapshot()
    )

    override val automationSnapshot:
            StateFlow<AutomationSnapshot> = mutableSnapshot

    // 返回当前内存快照。
    override fun currentSnapshot(): AutomationSnapshot {
        return mutableSnapshot.value
    }

    // 同步转换并保存内存快照。
    override fun updateSnapshot(
        transform: (AutomationSnapshot) -> AutomationSnapshot
    ): AutomationSnapshot {
        val updated = transform(mutableSnapshot.value)
        mutableSnapshot.value = updated
        return updated
    }

    // 领取第一个到期的待执行命令。
    override fun claimNextCommand(
        workerId: String,
        nowMillis: Long,
        leaseDurationMillis: Long
    ): MapCommand? {
        val snapshot = mutableSnapshot.value
        val commandIndex = snapshot.commands.indexOfFirst { command ->
            command.status == MapCommandStatus.PENDING &&
                    command.availableAtMillis <= nowMillis
        }
        if (commandIndex < 0) {
            return null
        }
        val claimed = snapshot.commands[commandIndex].copy(
            status = MapCommandStatus.CLAIMED,
            workerId = workerId,
            leaseUntilMillis = nowMillis + leaseDurationMillis
        )
        mutableSnapshot.value = snapshot.copy(
            commands = snapshot.commands.toMutableList().apply {
                this[commandIndex] = claimed
            }
        )
        return claimed
    }

    // 更新内存诊断状态。
    override fun updateDiagnostics(
        diagnostics: AccessibilityDiagnostics
    ) {
        updateSnapshot { snapshot ->
            snapshot.copy(diagnostics = diagnostics)
        }
    }

    // 更新内存自动化状态。
    override fun updateAutomationStatus(
        statusText: String,
        source: String,
        level: AutomationLogLevel
    ) {
        updateSnapshot { snapshot ->
            snapshot.copy(
                automationStatus = statusText,
                runtimeLogs = snapshot.runtimeLogs +
                        AutomationLogEntry(
                            timestampMillis = 0L,
                            level = level,
                            source = source,
                            message = statusText
                        )
            )
        }
    }

    // 保存待测试消费者领取的分屏请求。
    override fun requestAutoSplitLaunch(requestedAtMillis: Long) {
        updateSnapshot { snapshot ->
            snapshot.copy(autoSplitRequestedAtMillis = requestedAtMillis)
        }
    }

    // 领取并清除测试快照中的分屏请求。
    override fun consumeAutoSplitLaunchRequest(): Long? {
        val requestedAtMillis =
            mutableSnapshot.value.autoSplitRequestedAtMillis
        if (requestedAtMillis <= 0L) {
            return null
        }
        updateSnapshot { snapshot ->
            snapshot.copy(autoSplitRequestedAtMillis = 0L)
        }
        return requestedAtMillis
    }

    // 清除测试快照中的分屏请求。
    override fun clearPendingAutoSplitLaunchRequest() {
        updateSnapshot { snapshot ->
            snapshot.copy(autoSplitRequestedAtMillis = 0L)
        }
    }

    // 清空测试快照中的运行日志。
    override fun clearRuntimeLogs() {
        updateSnapshot { snapshot ->
            snapshot.copy(runtimeLogs = emptyList())
        }
    }

    // 清空内存快照。
    override fun clearAll(): Boolean {
        mutableSnapshot.value = AutomationSnapshot()
        return true
    }
}
