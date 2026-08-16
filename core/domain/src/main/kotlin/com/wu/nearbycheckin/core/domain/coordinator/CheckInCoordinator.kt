// @Time: 2026/07/20 18:02
// @Author: MrWu
// @File: CheckInCoordinator.kt
// @Software: Android Studio


package com.wu.nearbycheckin.core.domain.coordinator


import java.util.UUID

import com.wu.nearbycheckin.core.common.time.AppClock
import com.wu.nearbycheckin.core.domain.evaluator.CandidateDecision
import com.wu.nearbycheckin.core.domain.evaluator.CandidateEvaluationContext
import com.wu.nearbycheckin.core.domain.evaluator.CandidateEvaluator
import com.wu.nearbycheckin.core.model.AutomationSession
import com.wu.nearbycheckin.core.model.AutomationSessionStatus
import com.wu.nearbycheckin.core.model.AutomationSnapshot
import com.wu.nearbycheckin.core.model.CheckInTransaction
import com.wu.nearbycheckin.core.model.DualMapState
import com.wu.nearbycheckin.core.model.DualMapTransactionStatus
import com.wu.nearbycheckin.core.model.MapCommand
import com.wu.nearbycheckin.core.model.MapCommandStatus
import com.wu.nearbycheckin.core.model.MapOperationResult
import com.wu.nearbycheckin.core.model.MapOperationStatus
import com.wu.nearbycheckin.core.model.MapProvider
import com.wu.nearbycheckin.core.model.NearbyPlaceCandidate
import com.wu.nearbycheckin.core.domain.repository.CheckInTransactionRepository
import com.wu.nearbycheckin.core.domain.statemachine.DualMapEvent
import com.wu.nearbycheckin.core.domain.statemachine.DualMapStateMachine


/**
 * 保存一次候选提交的决策与关联事务。
 */
data class CandidateSubmissionResult(
    val decision: CandidateDecision,
    val transactionId: String = ""
)

/**
 * 协调候选、并行 Provider 队列、状态机和恢复流程。
 */
class CheckInCoordinator(
    private val repository: CheckInTransactionRepository,
    private val candidateEvaluator: CandidateEvaluator,
    private val stateMachine: DualMapStateMachine,
    private val clock: AppClock
) {

    private val coordinatorLock = Any()
    private val snapshotReducer =
        CheckInCoordinatorSnapshotReducer(stateMachine)

    // 创建或复用当前正在运行的自动化会话。
    fun startSession(): AutomationSession = synchronized(coordinatorLock) {
        val nowMillis = clock.nowMillis()
        var session: AutomationSession? = null

        repository.updateSnapshot { snapshot ->
            val existingSession = snapshot.session
            if (existingSession?.status == AutomationSessionStatus.RUNNING) {
                session = existingSession
                return@updateSnapshot prepareRunnableSnapshot(
                    snapshot = snapshot,
                    nowMillis = nowMillis
                )
            }

            val newSession = AutomationSession(
                sessionId = UUID.randomUUID().toString(),
                startedAtMillis = nowMillis,
                stoppedAtMillis = 0L,
                status = AutomationSessionStatus.RUNNING
            )
            session = newSession
            prepareRunnableSnapshot(
                snapshot = snapshot.copy(
                    session = newSession,
                    automationStatus = "巡航会话已启动"
                ),
                nowMillis = nowMillis
            )
        }

        checkNotNull(session)
    }

    // 停止当前会话并取消尚未进入终态的事务与命令。
    fun stopSession() = synchronized(coordinatorLock) {
        val nowMillis = clock.nowMillis()
        repository.updateSnapshot { snapshot ->
            val stoppedSession = snapshot.session?.copy(
                stoppedAtMillis = nowMillis,
                status = AutomationSessionStatus.STOPPED
            )
            val cancelledTransactions =
                snapshot.transactions.map { transaction ->
                    if (transaction.state.status in TERMINAL_STATUSES) {
                        transaction
                    } else {
                        transaction.copy(
                            state = transaction.state.copy(
                                status = DualMapTransactionStatus.CANCELLED,
                                blockedReason = "用户停止巡航"
                            ),
                            updatedAtMillis = nowMillis
                        )
                    }
                }
            val completedCommands = snapshot.commands.map { command ->
                if (command.status == MapCommandStatus.COMPLETED) {
                    command
                } else {
                    command.copy(
                        status = MapCommandStatus.COMPLETED,
                        completedAtMillis = nowMillis
                    )
                }
            }

            snapshot.copy(
                session = stoppedSession,
                transactions = cancelledTransactions,
                commands = completedCommands,
                automationStatus = "巡航会话已停止"
            )
        }
    }

    // 评估并幂等提交一个标准化候选。
    fun submitCandidate(
        candidate: NearbyPlaceCandidate
    ): CandidateSubmissionResult = synchronized(coordinatorLock) {
        val nowMillis = clock.nowMillis()
        var submissionResult: CandidateSubmissionResult? = null

        repository.updateSnapshot { snapshot ->
            val mergedCandidate =
                snapshotReducer.mergeCandidateObservation(
                snapshot = snapshot,
                candidate = candidate
            )
            val snapshotWithObservation = snapshot.copy(
                candidates = snapshotReducer.upsertCandidate(
                    candidates = snapshot.candidates,
                    candidate = mergedCandidate
                )
            )
            val decision = candidateEvaluator.evaluate(
                candidate = mergedCandidate,
                context = CandidateEvaluationContext(
                    snapshot = snapshotWithObservation,
                    nowMillis = nowMillis
                )
            )

            if (decision !is CandidateDecision.Accept) {
                submissionResult = CandidateSubmissionResult(decision)
                return@updateSnapshot snapshotWithObservation.copy(
                    automationStatus =
                        snapshotReducer.decisionStatusText(
                            decision = decision,
                            placeName = mergedCandidate.name
                        )
                )
            }

            val session = snapshotWithObservation.session?.takeIf {
                it.status == AutomationSessionStatus.RUNNING
            } ?: AutomationSession(
                sessionId = UUID.randomUUID().toString(),
                startedAtMillis = nowMillis,
                stoppedAtMillis = 0L,
                status = AutomationSessionStatus.RUNNING
            )
            val transaction = CheckInTransaction(
                transactionId = UUID.randomUUID().toString(),
                sessionId = session.sessionId,
                candidate = mergedCandidate,
                state = DualMapState(
                    status = DualMapTransactionStatus.QUEUED
                ),
                createdAtMillis = nowMillis,
                updatedAtMillis = nowMillis
            )
            submissionResult = CandidateSubmissionResult(
                decision = decision,
                transactionId = transaction.transactionId
            )

            prepareRunnableSnapshot(
                snapshot = snapshotWithObservation.copy(
                    session = session,
                    transactions = snapshotWithObservation.transactions +
                            transaction,
                    automationStatus = "候选已入队：${mergedCandidate.name}"
                ),
                nowMillis = nowMillis
            )
        }

        checkNotNull(submissionResult)
    }

    // 在领取命令前推进已经到期的重试状态。
    fun claimNextCommand(
        provider: MapProvider,
        workerId: String,
        leaseDurationMillis: Long = DEFAULT_COMMAND_LEASE_MILLIS
    ): MapCommand? = synchronized(coordinatorLock) {
        val nowMillis = clock.nowMillis()
        processDueRetry(nowMillis)
        repository.claimNextCommand(
            provider = provider,
            workerId = workerId,
            nowMillis = nowMillis,
            leaseDurationMillis = leaseDurationMillis
        )
    }

    // 幂等回写地图结果并原子推进事务状态。
    fun completeCommand(
        commandId: String,
        result: MapOperationResult
    ): Boolean = synchronized(coordinatorLock) {
        val nowMillis = clock.nowMillis()
        var completed = false

        repository.updateSnapshot { snapshot ->
            val commandIndex = snapshot.commands.indexOfFirst { command ->
                command.commandId == commandId
            }
            if (commandIndex < 0) {
                return@updateSnapshot snapshot
            }

            val command = snapshot.commands[commandIndex]
            if (command.status == MapCommandStatus.COMPLETED) {
                return@updateSnapshot snapshot
            }

            val transactionIndex =
                snapshot.transactions.indexOfFirst { transaction ->
                    transaction.transactionId == command.transactionId
                }
            if (transactionIndex < 0) {
                return@updateSnapshot snapshot
            }

            val normalizedResult = result.copy(
                commandId = command.commandId,
                transactionId = command.transactionId
            )
            val completedCommand = command.copy(
                status = MapCommandStatus.COMPLETED,
                completedAtMillis = nowMillis,
                result = normalizedResult
            )
            val commands = snapshot.commands.toMutableList().apply {
                this[commandIndex] = completedCommand
            }
            val transaction = snapshot.transactions[transactionIndex]
            val transition = stateMachine.reduce(
                state = transaction.state,
                event = DualMapEvent.MapResultReceived(
                    result = normalizedResult,
                    nowMillis = nowMillis
                )
            )
            var updatedSnapshot = snapshotReducer.applyTransition(
                snapshot = snapshot.copy(commands = commands),
                transaction = transaction,
                transition = transition,
                nowMillis = nowMillis
            )
            updatedSnapshot = reconcileAmapRealLocations(
                snapshot = updatedSnapshot,
                nowMillis = nowMillis
            )
            if (transition.newState.status in TERMINAL_STATUSES) {
                updatedSnapshot =
                    prepareRunnableSnapshot(
                        snapshot = updatedSnapshot,
                    nowMillis = nowMillis
                )
            }

            completed = true
            updatedSnapshot
        }

        completed
    }

    // 由用户显式恢复当前被登录、验证码或权限阻断的事务。
    fun manualResume(): Boolean = synchronized(coordinatorLock) {
        val nowMillis = clock.nowMillis()
        var resumed = false

        repository.updateSnapshot { snapshot ->
            val transaction = snapshot.activeTransaction
                ?: return@updateSnapshot snapshot
            val transition = stateMachine.reduce(
                state = transaction.state,
                event = DualMapEvent.ManualResume(nowMillis)
            )
            if (transition.newState == transaction.state) {
                return@updateSnapshot snapshot
            }

            resumed = true
            snapshotReducer.applyTransition(
                snapshot = snapshot,
                transaction = transaction,
                transition = transition,
                nowMillis = nowMillis
            )
        }

        resumed
    }

    // 在进程恢复后重新启动可执行的排队事务。
    fun resumePendingTransactions() = synchronized(coordinatorLock) {
        repository.updateSnapshot { snapshot ->
            prepareRunnableSnapshot(
                snapshot = snapshot,
                nowMillis = clock.nowMillis()
            )
        }
    }

    // 推进当前已经到达退避时间的失败端。
    private fun processDueRetry(nowMillis: Long) {
        repository.updateSnapshot { snapshot ->
            snapshot.transactions.fold(snapshot) {
                    currentSnapshot, originalTransaction ->
                val transaction = currentSnapshot.transactions
                    .firstOrNull { item ->
                        item.transactionId ==
                                originalTransaction.transactionId
                    } ?: return@fold currentSnapshot
                val transition = stateMachine.reduce(
                    state = transaction.state,
                    event = DualMapEvent.RetryDue(nowMillis)
                )
                if (transition.newState == transaction.state) {
                    currentSnapshot
                } else {
                    snapshotReducer.applyTransition(
                        snapshot = currentSnapshot,
                        transaction = transaction,
                        transition = transition,
                        nowMillis = nowMillis
                    )
                }
            }
        }
    }

    // 启动全部排队事务并复用已经完成的高德真实位置结果。
    private fun prepareRunnableSnapshot(
        snapshot: AutomationSnapshot,
        nowMillis: Long
    ): AutomationSnapshot {
        return reconcileAmapRealLocations(
            snapshot = snapshotReducer.startNextIfPossible(
                snapshot = snapshot,
                nowMillis = nowMillis
            ),
            nowMillis = nowMillis
        )
    }

    // 把高德领导事务终态传播到同一真实位置的其他活动事务。
    private fun reconcileAmapRealLocations(
        snapshot: AutomationSnapshot,
        nowMillis: Long
    ): AutomationSnapshot {
        var updatedSnapshot = snapshot
        val leaderIds = snapshot.transactions
            .asSequence()
            .filter { transaction ->
                transaction.state.amapTerminal &&
                        transaction.state.amapResult != null &&
                        AmapRealLocationDeduplicationPolicy
                            .isLocationLeader(
                                snapshot = snapshot,
                                transactionId = transaction.transactionId
                            )
            }
            .sortedBy { transaction -> transaction.createdAtMillis }
            .map { transaction -> transaction.transactionId }
            .toList()
        leaderIds.forEach { leaderId ->
            val leader = updatedSnapshot.transactions.firstOrNull { item ->
                item.transactionId == leaderId
            } ?: return@forEach
            val leaderResult = leader.state.amapResult ?: return@forEach
            val followerIds = updatedSnapshot.transactions
                .asSequence()
                .filter { follower ->
                    follower.transactionId != leader.transactionId &&
                            follower.sessionId == leader.sessionId &&
                            !follower.state.amapTerminal &&
                            follower.state.status !in TERMINAL_STATUSES &&
                            AmapRealLocationDeduplicationPolicy
                                .isSameRealLocation(
                                    leader.candidate,
                                    follower.candidate
                                )
                }
                .map { follower -> follower.transactionId }
                .toList()
            followerIds.forEach { followerId ->
                updatedSnapshot = reuseAmapResult(
                    snapshot = updatedSnapshot,
                    followerId = followerId,
                    leaderResult = leaderResult,
                    nowMillis = nowMillis
                )
            }
        }
        return updatedSnapshot
    }

    // 关闭重复高德命令并将领导事务结果写入跟随事务。
    private fun reuseAmapResult(
        snapshot: AutomationSnapshot,
        followerId: String,
        leaderResult: MapOperationResult,
        nowMillis: Long
    ): AutomationSnapshot {
        val follower = snapshot.transactions.firstOrNull { item ->
            item.transactionId == followerId
        } ?: return snapshot
        val activeAmapCommands = snapshot.commands.filter { command ->
            command.transactionId == followerId &&
                    command.provider == MapProvider.AMAP &&
                    command.status != MapCommandStatus.COMPLETED
        }
        val reusedStatus = if (leaderResult.status.isSuccessful()) {
            MapOperationStatus.ALREADY_COMPLETED
        } else {
            leaderResult.status
        }
        val primaryCommandId = activeAmapCommands.firstOrNull()
            ?.commandId
            .orEmpty()
        val reusedResult = leaderResult.copy(
            status = reusedStatus,
            completedAt = nowMillis,
            attempts = follower.state.amapAttempts,
            failureCode = if (reusedStatus.isSuccessful()) {
                ""
            } else {
                AMAP_REAL_LOCATION_DUPLICATE_CODE
            },
            failureMessage = if (reusedStatus.isSuccessful()) {
                "同一真实位置已完成高德打卡，跳过重复操作"
            } else {
                "同一真实位置的高德尝试已结束，跳过重复操作"
            },
            evidenceText = (
                    leaderResult.evidenceText +
                            "|dedupe=amap_real_location"
                    ).take(MAX_REUSED_EVIDENCE_LENGTH),
            commandId = primaryCommandId,
            transactionId = followerId
        )
        val commands = snapshot.commands.map { command ->
            if (command in activeAmapCommands) {
                command.copy(
                    status = MapCommandStatus.COMPLETED,
                    completedAtMillis = nowMillis,
                    result = reusedResult.copy(
                        commandId = command.commandId
                    )
                )
            } else {
                command
            }
        }
        val transition = stateMachine.reduce(
            state = follower.state,
            event = DualMapEvent.ProviderResultReused(
                result = reusedResult,
                nowMillis = nowMillis
            )
        )
        return snapshotReducer.applyTransition(
            snapshot = snapshot.copy(commands = commands),
            transaction = follower,
            transition = transition,
            nowMillis = nowMillis
        )
    }

    // 判断地图结果是否已满足该 Provider 的完成条件。
    private fun MapOperationStatus.isSuccessful(): Boolean {
        return this == MapOperationStatus.SUCCESS ||
                this == MapOperationStatus.ALREADY_COMPLETED
    }

    private companion object {
        const val DEFAULT_COMMAND_LEASE_MILLIS = 120_000L
        const val AMAP_REAL_LOCATION_DUPLICATE_CODE =
            "AMAP_REAL_LOCATION_DUPLICATE_SKIPPED"
        const val MAX_REUSED_EVIDENCE_LENGTH = 2_000

        val TERMINAL_STATUSES = setOf(
            DualMapTransactionStatus.COMPLETED,
            DualMapTransactionStatus.FAILED,
            DualMapTransactionStatus.CANCELLED
        )
    }
}
