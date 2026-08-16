// @Time: 2026/08/16 02:09
// @Author: MrWu
// @File: ProviderAutomationCoordinator.kt
// @Software: Android Studio


package com.wu.nearbycheckin.core.domain.coordinator


import java.util.UUID

import com.wu.nearbycheckin.core.common.time.AppClock
import com.wu.nearbycheckin.core.domain.repository.CheckInTransactionRepository
import com.wu.nearbycheckin.core.domain.statemachine.ProviderAutomationEvent
import com.wu.nearbycheckin.core.domain.statemachine.ProviderStateMachine
import com.wu.nearbycheckin.core.model.AutomationSessionStatus
import com.wu.nearbycheckin.core.model.LocationSnapshot
import com.wu.nearbycheckin.core.model.MapOperationEvidence
import com.wu.nearbycheckin.core.model.MapOperationStatus
import com.wu.nearbycheckin.core.model.MapProvider
import com.wu.nearbycheckin.core.model.ProviderAutomationState
import com.wu.nearbycheckin.core.model.ProviderCheckInTask
import com.wu.nearbycheckin.core.model.ProviderPlaceCandidate
import com.wu.nearbycheckin.core.model.ProviderTaskStatus


/**
 * 串行协调 Provider 候选、任务、完成键、位置刷新与恢复。
 */
class ProviderAutomationCoordinator(
    private val repository: CheckInTransactionRepository,
    private val clock: AppClock,
    private val stateMachine: ProviderStateMachine = ProviderStateMachine(),
    private val taskIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val policy: ProviderAutomationPolicy = ProviderAutomationPolicy()
) {
    private val lock = Any()
    private val locationFreshnessPolicy = ProviderLocationFreshnessPolicy()

    // 保存最新位置并在不中断原子任务的前提下标记百度列表过期。
    fun updateLocation(location: LocationSnapshot) = synchronized(lock) {
        val nowMillis = clock.nowMillis()
        if (!locationFreshnessPolicy.isFresh(location, nowMillis)) {
            return@synchronized
        }
        repository.updateSnapshot { snapshot ->
            val currentState = snapshot.providerRuntime.baiduState
            snapshot.copy(
                providerRuntime = snapshot.providerRuntime.copy(
                    latestLocation = location,
                    baiduListStale = true,
                    baiduState = if (
                        currentState in BUSY_PROVIDER_STATES ||
                        currentState == ProviderAutomationState.BLOCKED
                    ) {
                        currentState
                    } else {
                        ProviderAutomationState.READY
                    }
                )
            )
        }
    }

    // 记录静止心跳并请求百度在安全点刷新候选列表。
    fun heartbeat(provider: MapProvider = MapProvider.BAIDU) =
        synchronized(lock) {
            markListStale(provider)
        }

    // 标记 Provider 候选列表已经过期且不抢占活动任务。
    fun markListStale(provider: MapProvider) = synchronized(lock) {
        repository.updateSnapshot { snapshot ->
            val runtime = snapshot.providerRuntime
            when (provider) {
                MapProvider.BAIDU -> snapshot.copy(
                    providerRuntime = runtime.copy(
                        baiduListStale = true,
                        baiduState = runtime.baiduState.readyIfIdle()
                    )
                )
                MapProvider.AMAP -> snapshot.copy(
                    providerRuntime = runtime.copy(
                        amapListStale = true,
                        amapState = runtime.amapState.readyIfIdle()
                    )
                )
            }
        }
    }

    // 开始读取指定 Provider 候选列表。
    fun beginCandidateRefresh(provider: MapProvider) = synchronized(lock) {
        repository.updateSnapshot { snapshot ->
            snapshot.withProviderState(
                provider = provider,
                event = ProviderAutomationEvent.RefreshRequested
            )
        }
    }

    // 以一次原子更新替换候选、导入 UI 完成键并补齐未重复任务。
    fun replaceCandidates(
        provider: MapProvider,
        candidates: List<ProviderPlaceCandidate>,
        listFingerprint: String,
        profileVersion: String = ""
    ) = synchronized(lock) {
        val nowMillis = clock.nowMillis()
        repository.updateSnapshot { snapshot ->
            val validCandidates = candidates
                .filter { candidate -> candidate.provider == provider }
                .distinctBy { candidate -> candidate.toPlaceKey() }
            val visibleKeys = validCandidates.mapTo(mutableSetOf()) {
                candidate -> candidate.toPlaceKey()
            }
            val uiCompletedKeys = validCandidates
                .filter(ProviderPlaceCandidate::completed)
                .mapTo(mutableSetOf()) { candidate ->
                    candidate.toPlaceKey()
                }
            val completedKeys = snapshot.providerCompletedKeys +
                    uiCompletedKeys
            val latestCandidatesByKey = validCandidates.associateBy {
                candidate -> candidate.toPlaceKey()
            }
            val existingTasks = snapshot.providerTasks.map { task ->
                if (
                    task.provider == provider &&
                    task.candidateKey in completedKeys &&
                    task.status !in TERMINAL_TASK_STATUSES
                ) {
                    task.copy(
                        status = ProviderTaskStatus.SKIPPED,
                        updatedAtMillis = nowMillis,
                        lastError = "地图 UI 或永久完成键已标记完成"
                    )
                } else if (
                    task.provider == provider &&
                    task.status == ProviderTaskStatus.FAILED &&
                    profileVersion.isNotBlank() &&
                    task.evidence?.profileVersion != profileVersion
                ) {
                    latestCandidatesByKey[task.candidateKey]?.let {
                        candidate -> task.copy(
                            candidate = candidate,
                            status = ProviderTaskStatus.QUEUED,
                            attempts = 0,
                            commandId = "",
                            updatedAtMillis = nowMillis,
                            availableAtMillis = nowMillis,
                            leaseOwner = "",
                            leaseUntilMillis = 0L,
                            lastError = "",
                            evidence = null
                        )
                    } ?: task
                } else if (
                    task.provider == provider &&
                    task.status !in TERMINAL_TASK_STATUSES
                ) {
                    latestCandidatesByKey[task.candidateKey]?.let {
                        candidate -> task.copy(candidate = candidate)
                    } ?: task
                } else {
                    task
                }
            }
            val existingKeys = existingTasks
                .filter { task ->
                    task.provider == provider &&
                            task.status != ProviderTaskStatus.CANCELLED
                }
                .mapTo(mutableSetOf()) { task -> task.candidateKey }
            val sessionId = snapshot.session
                ?.takeIf { session ->
                    session.status == AutomationSessionStatus.RUNNING
                }
                ?.sessionId
                .orEmpty()
            val newTasks = validCandidates.mapNotNull { candidate ->
                val key = candidate.toPlaceKey()
                if (
                    candidate.completed ||
                    key in completedKeys ||
                    key in existingKeys
                ) {
                    null
                } else {
                    ProviderCheckInTask(
                        taskId = taskIdFactory(),
                        sessionId = sessionId,
                        provider = provider,
                        candidate = candidate,
                        candidateKey = key,
                        status = ProviderTaskStatus.QUEUED,
                        attempts = 0,
                        commandId = "",
                        createdAtMillis = nowMillis,
                        updatedAtMillis = nowMillis,
                        availableAtMillis = nowMillis
                    )
                }
            }
            val tasks = existingTasks + newTasks
            val hasExecutable = tasks.any { task ->
                task.provider == provider &&
                        task.candidateKey in visibleKeys &&
                        task.candidateKey !in completedKeys &&
                        task.status in EXECUTABLE_TASK_STATUSES
            }
            snapshot.copy(
                providerCandidates =
                    snapshot.providerCandidates.filterNot { candidate ->
                        candidate.provider == provider
                    } + validCandidates,
                providerTasks = tasks,
                providerCompletedKeys = completedKeys
            ).withCandidateListResult(
                provider = provider,
                listFingerprint = listFingerprint,
                hasExecutableCandidate = hasExecutable
            )
        }
    }

    // 原子领取 Provider 下一任务并进入选择状态。
    fun claimNextTask(
        provider: MapProvider,
        workerId: String
    ): ProviderCheckInTask? = synchronized(lock) {
        val task = repository.claimNextProviderTask(
            provider = provider,
            workerId = workerId,
            nowMillis = clock.nowMillis(),
            leaseDurationMillis = policy.leaseDurationMillis
        ) ?: return@synchronized null
        repository.updateSnapshot { snapshot ->
            snapshot.withProviderState(
                provider = provider,
                event = ProviderAutomationEvent.CandidateSelected
            )
        }
        task
    }

    // 将已点击候选任务推进到验证状态并关联命令标识。
    fun beginVerification(taskId: String, commandId: String): Boolean =
        synchronized(lock) {
            var updated = false
            repository.updateSnapshot { snapshot ->
                val index = snapshot.providerTasks.indexOfFirst { task ->
                    task.taskId == taskId &&
                            task.status == ProviderTaskStatus.ACTIVE
                }
                if (index < 0) {
                    return@updateSnapshot snapshot
                }
                val task = snapshot.providerTasks[index].copy(
                    status = ProviderTaskStatus.VERIFYING,
                    commandId = commandId,
                    updatedAtMillis = clock.nowMillis()
                )
                updated = true
                snapshot.copy(
                    providerTasks = snapshot.providerTasks
                        .toMutableList()
                        .apply { this[index] = task }
                ).withProviderState(
                    provider = task.provider,
                    event = ProviderAutomationEvent.VerificationStarted
                )
            }
            updated
        }

    // 根据证据完成、跳过、阻断或退避重试 Provider 任务。
    fun finishTask(
        taskId: String,
        evidence: MapOperationEvidence
    ): Boolean = synchronized(lock) {
        val snapshot = repository.currentSnapshot()
        val task = snapshot.providerTasks.firstOrNull { item ->
            item.taskId == taskId
        } ?: return@synchronized false
        val nowMillis = clock.nowMillis()
        when (evidence.resultStatus) {
            MapOperationStatus.SUCCESS -> repository.completeProviderTask(
                taskId = taskId,
                status = ProviderTaskStatus.COMPLETED,
                evidence = evidence,
                errorMessage = "",
                nowMillis = nowMillis
            )
            MapOperationStatus.ALREADY_COMPLETED ->
                repository.completeProviderTask(
                    taskId = taskId,
                    status = ProviderTaskStatus.SKIPPED,
                    evidence = evidence,
                    errorMessage = "",
                    nowMillis = nowMillis
                )
            MapOperationStatus.BLOCKED,
            MapOperationStatus.UNSUPPORTED ->
                repository.completeProviderTask(
                    taskId = taskId,
                    status = ProviderTaskStatus.BLOCKED,
                    evidence = evidence,
                    errorMessage = evidence.message,
                    nowMillis = nowMillis
                )
            MapOperationStatus.FAILED,
            MapOperationStatus.TIMEOUT,
            MapOperationStatus.UNKNOWN ->
                scheduleRetryOrFail(task, evidence, nowMillis)
        }.also { completed ->
            updateStateAfterTask(task.provider, evidence, completed)
        }
    }

    // 把插件页面、动作和证据摘要写入唯一运行状态。
    fun updatePluginDiagnostics(
        provider: MapProvider,
        page: String,
        action: String,
        evidence: String
    ) = synchronized(lock) {
        repository.updateSnapshot { snapshot ->
            val evidenceText = evidence.take(MAX_DIAGNOSTIC_LENGTH)
            val runtime = snapshot.providerRuntime
            snapshot.copy(
                providerRuntime = when (provider) {
                    MapProvider.BAIDU -> runtime.copy(
                        lastPluginPage = "${provider.name}:$page",
                        lastAction = action,
                        lastEvidence = evidenceText,
                        baiduPluginPage = page,
                        baiduPluginAction = action,
                        baiduPluginEvidence = evidenceText
                    )
                    MapProvider.AMAP -> runtime.copy(
                        lastPluginPage = "${provider.name}:$page",
                        lastAction = action,
                        lastEvidence = evidenceText,
                        amapPluginPage = page,
                        amapPluginAction = action,
                        amapPluginEvidence = evidenceText
                    )
                }
            )
        }
    }

    // 恢复租约过期或退避到期的百度任务并保留完成键。
    fun resumePendingTasks(provider: MapProvider = MapProvider.BAIDU) =
        synchronized(lock) {
            val nowMillis = clock.nowMillis()
            repository.updateSnapshot { snapshot ->
                val tasks = snapshot.providerTasks.map { task ->
                    if (
                        task.provider == provider &&
                        task.candidateKey !in
                        snapshot.providerCompletedKeys &&
                        (
                                task.status ==
                                    ProviderTaskStatus.RETRY_WAIT &&
                                    task.availableAtMillis <= nowMillis ||
                                task.status == ProviderTaskStatus.ACTIVE &&
                                    task.leaseUntilMillis <= nowMillis ||
                                task.status ==
                                    ProviderTaskStatus.VERIFYING &&
                                    task.leaseUntilMillis <= nowMillis
                                )
                    ) {
                        task.copy(
                            status = ProviderTaskStatus.QUEUED,
                            leaseOwner = "",
                            leaseUntilMillis = 0L,
                            updatedAtMillis = nowMillis
                        )
                    } else {
                        task
                    }
                }
                snapshot.copy(providerTasks = tasks)
                    .withProviderState(
                        provider = provider,
                        event = ProviderAutomationEvent.Enable
                    )
            }
        }

    // 手动恢复指定 Provider 的阻断任务。
    fun manualResume(provider: MapProvider = MapProvider.BAIDU): Boolean =
        synchronized(lock) {
            var resumed = false
            val nowMillis = clock.nowMillis()
            repository.updateSnapshot { snapshot ->
                val tasks = snapshot.providerTasks.map { task ->
                    if (
                        task.provider == provider &&
                        task.status == ProviderTaskStatus.BLOCKED &&
                        task.candidateKey !in snapshot.providerCompletedKeys
                    ) {
                        resumed = true
                        task.copy(
                            status = ProviderTaskStatus.QUEUED,
                            availableAtMillis = nowMillis,
                            leaseOwner = "",
                            leaseUntilMillis = 0L,
                            lastError = "",
                            updatedAtMillis = nowMillis
                        )
                    } else {
                        task
                    }
                }
                if (!resumed) {
                    snapshot
                } else {
                    snapshot.copy(providerTasks = tasks)
                        .withProviderState(
                            provider = provider,
                            event = ProviderAutomationEvent.ManualResume
                        )
                }
            }
            resumed
        }

    // 按尝试次数安排指数退避或终结任务。
    private fun scheduleRetryOrFail(
        task: ProviderCheckInTask,
        evidence: MapOperationEvidence,
        nowMillis: Long
    ): Boolean {
        var updated = false
        repository.updateSnapshot { snapshot ->
            val index = snapshot.providerTasks.indexOfFirst { item ->
                item.taskId == task.taskId
            }
            if (index < 0) {
                return@updateSnapshot snapshot
            }
            val current = snapshot.providerTasks[index]
            val exhausted = current.attempts >= policy.maxAttempts
            val next = current.copy(
                status = if (exhausted) {
                    ProviderTaskStatus.FAILED
                } else {
                    ProviderTaskStatus.RETRY_WAIT
                },
                availableAtMillis = if (exhausted) {
                    0L
                } else {
                    nowMillis + policy.retryDelayMillis(current.attempts)
                },
                leaseOwner = "",
                leaseUntilMillis = 0L,
                updatedAtMillis = nowMillis,
                lastError = evidence.message,
                evidence = evidence
            )
            updated = true
            snapshot.copy(
                providerTasks = snapshot.providerTasks.toMutableList()
                    .apply { this[index] = next }
            )
        }
        return updated
    }

    // 根据任务结果更新 Provider 页面状态并保留列表过期标记。
    private fun updateStateAfterTask(
        provider: MapProvider,
        evidence: MapOperationEvidence,
        completed: Boolean
    ) {
        if (!completed) {
            return
        }
        repository.updateSnapshot { snapshot ->
            val hasMore = snapshot.providerQueue(provider).any { task ->
                task.status == ProviderTaskStatus.QUEUED
            }
            val event = if (
                evidence.resultStatus == MapOperationStatus.BLOCKED ||
                evidence.resultStatus == MapOperationStatus.UNSUPPORTED
            ) {
                ProviderAutomationEvent.Blocked(evidence.message)
            } else {
                ProviderAutomationEvent.VerificationFinished(hasMore)
            }
            snapshot.withProviderState(provider, event)
        }
    }

    // 将候选读取结果写入 Provider 状态和列表诊断字段。
    private fun com.wu.nearbycheckin.core.model.AutomationSnapshot
        .withCandidateListResult(
            provider: MapProvider,
            listFingerprint: String,
            hasExecutableCandidate: Boolean
        ): com.wu.nearbycheckin.core.model.AutomationSnapshot {
        val transitioned = withProviderState(
            provider,
            ProviderAutomationEvent.CandidatesLoaded(
                hasExecutableCandidate
            )
        )
        return when (provider) {
            MapProvider.BAIDU -> transitioned.copy(
                providerRuntime = transitioned.providerRuntime.copy(
                    baiduListStale = false,
                    baiduListFingerprint = listFingerprint
                )
            )
            MapProvider.AMAP -> transitioned.copy(
                providerRuntime = transitioned.providerRuntime.copy(
                    amapListStale = false,
                    amapListFingerprint = listFingerprint
                )
            )
        }
    }

    // 使用纯状态机更新指定 Provider 的运行状态。
    private fun com.wu.nearbycheckin.core.model.AutomationSnapshot
        .withProviderState(
            provider: MapProvider,
            event: ProviderAutomationEvent
        ): com.wu.nearbycheckin.core.model.AutomationSnapshot {
        val current = when (provider) {
            MapProvider.BAIDU -> providerRuntime.baiduState
            MapProvider.AMAP -> providerRuntime.amapState
        }
        val next = stateMachine.reduce(current, event)
        return when (provider) {
            MapProvider.BAIDU -> copy(
                providerRuntime = providerRuntime.copy(
                    baiduState = next.state,
                    activeProvider = activeProviderFor(next.state, provider),
                    lastEvidence = next.blockedReason.ifBlank {
                        providerRuntime.lastEvidence
                    }
                )
            )
            MapProvider.AMAP -> copy(
                providerRuntime = providerRuntime.copy(
                    amapState = next.state,
                    activeProvider = activeProviderFor(next.state, provider),
                    lastEvidence = next.blockedReason.ifBlank {
                        providerRuntime.lastEvidence
                    }
                )
            )
        }
    }

    // 只在选择或验证阶段保留活动 Provider。
    private fun activeProviderFor(
        state: ProviderAutomationState,
        provider: MapProvider
    ): MapProvider? {
        return if (state in BUSY_PROVIDER_STATES) provider else null
    }

    // 空闲状态收到刷新信号时进入 READY，忙碌和阻断状态保持不变。
    private fun ProviderAutomationState.readyIfIdle():
            ProviderAutomationState {
        return if (this == ProviderAutomationState.IDLE) {
            ProviderAutomationState.READY
        } else {
            this
        }
    }

    private companion object {
        const val MAX_DIAGNOSTIC_LENGTH = 2_000

        val BUSY_PROVIDER_STATES = setOf(
            ProviderAutomationState.SELECTING,
            ProviderAutomationState.VERIFYING
        )
        val EXECUTABLE_TASK_STATUSES = setOf(
            ProviderTaskStatus.QUEUED,
            ProviderTaskStatus.ACTIVE,
            ProviderTaskStatus.VERIFYING,
            ProviderTaskStatus.RETRY_WAIT
        )
        val TERMINAL_TASK_STATUSES = setOf(
            ProviderTaskStatus.COMPLETED,
            ProviderTaskStatus.SKIPPED,
            ProviderTaskStatus.FAILED,
            ProviderTaskStatus.CANCELLED
        )
    }
}

/**
 * 保存 Provider 任务租约、重试次数与退避策略。
 */
data class ProviderAutomationPolicy(
    val leaseDurationMillis: Long = 60_000L,
    val maxAttempts: Int = 3,
    val initialRetryDelayMillis: Long = 2_000L,
    val maximumRetryDelayMillis: Long = 30_000L
) {

    // 根据已发出尝试次数计算有上限的指数退避时间。
    fun retryDelayMillis(attempts: Int): Long {
        val exponent = (attempts - 1).coerceIn(0, 10)
        return (initialRetryDelayMillis * (1L shl exponent))
            .coerceAtMost(maximumRetryDelayMillis)
    }
}
