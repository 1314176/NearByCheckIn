// @Time: 2026/07/20 18:43
// @Author: MrWu
// @File: CheckInCoordinatorSnapshotReducer.kt
// @Software: Android Studio


package com.wu.nearbycheckin.core.domain.coordinator


import java.util.UUID

import com.wu.nearbycheckin.core.domain.evaluator.CandidateDecision
import com.wu.nearbycheckin.core.model.AutomationSessionStatus
import com.wu.nearbycheckin.core.model.AutomationSnapshot
import com.wu.nearbycheckin.core.model.CheckInRecord
import com.wu.nearbycheckin.core.model.CheckInTransaction
import com.wu.nearbycheckin.core.model.DualMapTransactionStatus
import com.wu.nearbycheckin.core.model.MapCommand
import com.wu.nearbycheckin.core.model.MapCommandStatus
import com.wu.nearbycheckin.core.model.NearbyPlaceCandidate
import com.wu.nearbycheckin.core.domain.statemachine.DomainEffect
import com.wu.nearbycheckin.core.domain.statemachine.DualMapCommand
import com.wu.nearbycheckin.core.domain.statemachine.DualMapEvent
import com.wu.nearbycheckin.core.domain.statemachine.DualMapStateMachine
import com.wu.nearbycheckin.core.domain.statemachine.DualMapTransition


/**
 * 集中处理协调器使用的纯快照转换和命令持久化映射。
 */
internal class CheckInCoordinatorSnapshotReducer(
    private val stateMachine: DualMapStateMachine
) {

    // 在没有活动事务时启动最早入队的事务。
    fun startNextIfPossible(
        snapshot: AutomationSnapshot,
        nowMillis: Long
    ): AutomationSnapshot {
        if (
            snapshot.activeTransaction != null ||
            snapshot.session?.status != AutomationSessionStatus.RUNNING
        ) {
            return snapshot
        }

        val transaction = snapshot.queuedTransactions.firstOrNull()
            ?: return snapshot
        val transition = stateMachine.reduce(
            state = transaction.state,
            event = DualMapEvent.ExecutionStarted(nowMillis)
        )
        return applyTransition(
            snapshot = snapshot,
            transaction = transaction,
            transition = transition,
            nowMillis = nowMillis
        )
    }

    // 把纯状态机输出转换为持久化事务和命令。
    fun applyTransition(
        snapshot: AutomationSnapshot,
        transaction: CheckInTransaction,
        transition: DualMapTransition,
        nowMillis: Long
    ): AutomationSnapshot {
        val updatedTransaction = transaction.copy(
            state = transition.newState,
            updatedAtMillis = nowMillis
        )
        val transactions = snapshot.transactions.map { item ->
            if (item.transactionId == transaction.transactionId) {
                updatedTransaction
            } else {
                item
            }
        }
        val newCommands = transition.commands.map { command ->
            command.toPersistentCommand(
                transactionId = transaction.transactionId,
                nowMillis = nowMillis
            )
        }
        var updatedSnapshot = snapshot.copy(
            transactions = transactions,
            commands = snapshot.commands + newCommands,
            automationStatus = transitionStatusText(
                transition = transition,
                placeName = transaction.candidate.name
            )
        )

        if (DomainEffect.MarkPlaceCompleted in transition.effects) {
            updatedSnapshot = completePlace(
                snapshot = updatedSnapshot,
                transaction = updatedTransaction,
                nowMillis = nowMillis
            )
        }
        return updatedSnapshot
    }

    // 合并同一地点的首次发现时间与最新观测字段。
    fun mergeCandidateObservation(
        snapshot: AutomationSnapshot,
        candidate: NearbyPlaceCandidate
    ): NearbyPlaceCandidate {
        val existing = snapshot.candidates.firstOrNull { item ->
            item.unifiedPlaceId == candidate.unifiedPlaceId
        }
        return candidate.copy(
            firstSeenAtMillis = existing?.firstSeenAtMillis
                ?: candidate.firstSeenAtMillis
        )
    }

    // 幂等替换候选并限制持久化候选集合规模。
    fun upsertCandidate(
        candidates: List<NearbyPlaceCandidate>,
        candidate: NearbyPlaceCandidate
    ): List<NearbyPlaceCandidate> {
        return (
                candidates.filterNot { item ->
                    item.unifiedPlaceId == candidate.unifiedPlaceId
                } + candidate
                )
            .sortedByDescending { item ->
                item.observedAtMillis
            }
            .take(MAX_PERSISTED_CANDIDATES)
    }

    // 将候选决策转换为通知和诊断可读状态。
    fun decisionStatusText(
        decision: CandidateDecision,
        placeName: String
    ): String {
        return when (decision) {
            is CandidateDecision.Accept ->
                "候选已接受：$placeName"
            is CandidateDecision.Defer ->
                "候选暂缓：$placeName，原因=${decision.reason}"
            is CandidateDecision.Reject ->
                "候选已拒绝：$placeName，原因=${decision.reason}"
        }
    }

    // 将终态地点一次性写入记录和永久去重集合。
    private fun completePlace(
        snapshot: AutomationSnapshot,
        transaction: CheckInTransaction,
        nowMillis: Long
    ): AutomationSnapshot {
        val candidate = transaction.candidate
        val completedKeys =
            snapshot.completedPlaceKeys +
                    candidate.providerPlaceIds +
                    candidate.unifiedPlaceId
        val recordAlreadyExists = snapshot.records.any { record ->
            record.poiUid == candidate.unifiedPlaceId
        }
        val records = if (recordAlreadyExists) {
            snapshot.records
        } else {
            listOf(
                CheckInRecord(
                    id = transaction.transactionId,
                    poiUid = candidate.unifiedPlaceId,
                    poiName = candidate.name,
                    address = candidate.address,
                    category = candidate.category,
                    latitude = candidate.latitude,
                    longitude = candidate.longitude,
                    distanceMeters = candidate.distanceMeters,
                    stayDurationMillis =
                        (candidate.observedAtMillis -
                                candidate.firstSeenAtMillis)
                            .coerceAtLeast(0L),
                    checkInTimeMillis = nowMillis
                )
            ) + snapshot.records
        }
        return snapshot.copy(
            candidates = snapshot.candidates.filterNot { item ->
                item.unifiedPlaceId == candidate.unifiedPlaceId
            },
            records = records,
            completedPlaceKeys = completedKeys,
            automationStatus = "双地图打卡完成：${candidate.name}"
        )
    }

    // 将状态机地图命令转换为带幂等标识的持久化命令。
    private fun DualMapCommand.toPersistentCommand(
        transactionId: String,
        nowMillis: Long
    ): MapCommand {
        return when (this) {
            is DualMapCommand.ExecuteMapOperation -> MapCommand(
                commandId = UUID.randomUUID().toString(),
                transactionId = transactionId,
                provider = provider,
                attempt = attempt,
                createdAtMillis = nowMillis,
                availableAtMillis = availableAtMillis,
                status = MapCommandStatus.PENDING
            )
        }
    }

    // 将状态机输出转换为通知和诊断可读状态。
    private fun transitionStatusText(
        transition: DualMapTransition,
        placeName: String
    ): String {
        return when (transition.newState.status) {
            DualMapTransactionStatus.QUEUED ->
                "等待执行：$placeName"
            DualMapTransactionStatus.BAIDU_RUNNING ->
                "正在执行百度地图：$placeName"
            DualMapTransactionStatus.AMAP_RUNNING ->
                "正在执行高德地图：$placeName"
            DualMapTransactionStatus.PARTIAL_SUCCESS ->
                "部分成功，等待重试：$placeName"
            DualMapTransactionStatus.RETRY_WAIT ->
                "执行失败，等待重试：$placeName"
            DualMapTransactionStatus.COMPLETED ->
                "双地图打卡完成：$placeName"
            DualMapTransactionStatus.BLOCKED ->
                "需要人工处理：$placeName"
            DualMapTransactionStatus.FAILED ->
                "双地图打卡失败：$placeName"
            DualMapTransactionStatus.CANCELLED ->
                "事务已取消：$placeName"
        }
    }

    private companion object {
        const val MAX_PERSISTED_CANDIDATES = 100
    }
}
