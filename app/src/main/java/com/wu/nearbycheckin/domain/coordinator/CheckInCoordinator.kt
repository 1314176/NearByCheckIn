// @Time: 2026/07/20 12:00
// @Author: MrWu
// @File: CheckInCoordinator.kt
// @Software: Codex

package com.wu.nearbycheckin.domain.coordinator

import java.util.UUID

import com.wu.nearbycheckin.domain.evaluator.CandidateDecision
import com.wu.nearbycheckin.domain.evaluator.CandidateEvaluationContext
import com.wu.nearbycheckin.domain.evaluator.CandidateEvaluator
import com.wu.nearbycheckin.domain.model.AutomationSession
import com.wu.nearbycheckin.domain.model.AutomationSessionStatus
import com.wu.nearbycheckin.domain.model.AutomationSnapshot
import com.wu.nearbycheckin.domain.model.CheckInTransaction
import com.wu.nearbycheckin.domain.model.DualMapState
import com.wu.nearbycheckin.domain.model.DualMapTransactionStatus
import com.wu.nearbycheckin.domain.model.MapCommand
import com.wu.nearbycheckin.domain.model.MapCommandStatus
import com.wu.nearbycheckin.domain.model.MapOperationResult
import com.wu.nearbycheckin.domain.model.NearbyPlaceCandidate
import com.wu.nearbycheckin.domain.repository.CheckInTransactionRepository
import com.wu.nearbycheckin.domain.statemachine.DualMapEvent
import com.wu.nearbycheckin.domain.statemachine.DualMapStateMachine

/**
 * 为领域内核提供可替换的当前时间。
 */
fun interface AutomationClock {

    // 返回当前时间戳，单位为毫秒。
    fun nowMillis(): Long
}

/**
 * 保存一次候选提交的决策与关联事务。
 */
data class CandidateSubmissionResult(
    val decision: CandidateDecision,
    val transactionId: String = ""
)

/**
 * 串行协调候选、事务、状态机、命令队列和恢复流程。
 */
class CheckInCoordinator(
    private val repository: CheckInTransactionRepository,
    private val candidateEvaluator: CandidateEvaluator,
    private val stateMachine: DualMapStateMachine,
    private val clock: AutomationClock
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
                return@updateSnapshot snapshotReducer.startNextIfPossible(
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
            snapshotReducer.startNextIfPossible(
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

            snapshotReducer.startNextIfPossible(
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
        workerId: String,
        leaseDurationMillis: Long = DEFAULT_COMMAND_LEASE_MILLIS
    ): MapCommand? = synchronized(coordinatorLock) {
        val nowMillis = clock.nowMillis()
        processDueRetry(nowMillis)
        repository.claimNextCommand(
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
            if (transition.newState.status in TERMINAL_STATUSES) {
                updatedSnapshot =
                    snapshotReducer.startNextIfPossible(
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
            snapshotReducer.startNextIfPossible(
                snapshot = snapshot,
                nowMillis = clock.nowMillis()
            )
        }
    }

    // 推进当前已经到达退避时间的失败端。
    private fun processDueRetry(nowMillis: Long) {
        repository.updateSnapshot { snapshot ->
            val transaction = snapshot.activeTransaction
                ?: return@updateSnapshot snapshot
            val transition = stateMachine.reduce(
                state = transaction.state,
                event = DualMapEvent.RetryDue(nowMillis)
            )
            if (transition.newState == transaction.state) {
                snapshot
            } else {
                snapshotReducer.applyTransition(
                    snapshot = snapshot,
                    transaction = transaction,
                    transition = transition,
                    nowMillis = nowMillis
                )
            }
        }
    }

    private companion object {
        const val DEFAULT_COMMAND_LEASE_MILLIS = 120_000L

        val TERMINAL_STATUSES = setOf(
            DualMapTransactionStatus.COMPLETED,
            DualMapTransactionStatus.FAILED,
            DualMapTransactionStatus.CANCELLED
        )
    }
}
