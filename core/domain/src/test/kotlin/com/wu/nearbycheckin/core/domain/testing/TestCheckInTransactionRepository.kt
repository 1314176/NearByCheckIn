// @Time: 2026/07/26 02:49
// @Author: MrWu
// @File: TestCheckInTransactionRepository.kt
// @Software: Android Studio


package com.wu.nearbycheckin.core.domain.testing


import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

import com.wu.nearbycheckin.core.domain.repository.CheckInTransactionRepository
import com.wu.nearbycheckin.core.domain.coordinator.AmapRealLocationDeduplicationPolicy
import com.wu.nearbycheckin.core.model.AccessibilityDiagnostics
import com.wu.nearbycheckin.core.model.AutomationLogEntry
import com.wu.nearbycheckin.core.model.AutomationLogLevel
import com.wu.nearbycheckin.core.model.AutomationSnapshot
import com.wu.nearbycheckin.core.model.CheckInRecord
import com.wu.nearbycheckin.core.model.MapCommand
import com.wu.nearbycheckin.core.model.MapCommandStatus
import com.wu.nearbycheckin.core.model.MapOperationEvidence
import com.wu.nearbycheckin.core.model.MapProvider
import com.wu.nearbycheckin.core.model.ProviderCheckInTask
import com.wu.nearbycheckin.core.model.ProviderPlaceKey
import com.wu.nearbycheckin.core.model.ProviderTaskStatus


internal class TestCheckInTransactionRepository(
    initialSnapshot: AutomationSnapshot = AutomationSnapshot()
) : CheckInTransactionRepository {

    private val mutableSnapshot = MutableStateFlow(initialSnapshot)

    override val automationSnapshot:
        StateFlow<AutomationSnapshot> = mutableSnapshot

    // 返回当前内存快照。
    override fun currentSnapshot(): AutomationSnapshot {
        return mutableSnapshot.value
    }

    // 原子转换并保存内存快照。
    override fun updateSnapshot(
        transform: (AutomationSnapshot) -> AutomationSnapshot
    ): AutomationSnapshot {
        val updated = transform(mutableSnapshot.value)
        mutableSnapshot.value = updated
        return updated
    }

    // 领取指定 Provider 第一条已经到期的待执行命令。
    override fun claimNextCommand(
        provider: MapProvider,
        workerId: String,
        nowMillis: Long,
        leaseDurationMillis: Long
    ): MapCommand? {
        val snapshot = mutableSnapshot.value
        val commandIndex = snapshot.commands.indexOfFirst { command ->
            command.provider == provider &&
                    (
                            provider != MapProvider.AMAP ||
                                    AmapRealLocationDeduplicationPolicy
                                        .isLocationLeader(
                                            snapshot = snapshot,
                                            transactionId =
                                                command.transactionId
                                        )
                            ) &&
                    command.availableAtMillis <= nowMillis &&
                    (
                            command.status == MapCommandStatus.PENDING ||
                                    (
                                            command.status ==
                                                MapCommandStatus.CLAIMED &&
                                                    command.leaseUntilMillis <=
                                                    nowMillis
                                            )
                            )
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

    // 领取指定 Provider 第一条到期且未完成的地点任务。
    override fun claimNextProviderTask(
        provider: MapProvider,
        workerId: String,
        nowMillis: Long,
        leaseDurationMillis: Long
    ): ProviderCheckInTask? {
        val snapshot = mutableSnapshot.value
        val visibleKeys = snapshot.providerCandidates
            .asSequence()
            .filter { candidate -> candidate.provider == provider }
            .map { candidate -> candidate.toPlaceKey() }
            .toSet()
        val taskIndex = snapshot.providerTasks.indexOfFirst { task ->
            task.provider == provider &&
                    task.candidateKey in visibleKeys &&
                    task.candidateKey !in snapshot.providerCompletedKeys &&
                    task.availableAtMillis <= nowMillis &&
                    (
                            task.status == ProviderTaskStatus.QUEUED ||
                                    task.status ==
                                    ProviderTaskStatus.RETRY_WAIT ||
                                    (
                                            task.status ==
                                                ProviderTaskStatus.ACTIVE &&
                                                    task.leaseUntilMillis <=
                                                    nowMillis
                                            )
                            )
        }
        if (taskIndex < 0) {
            return null
        }
        val task = snapshot.providerTasks[taskIndex]
        val claimed = task.copy(
            status = ProviderTaskStatus.ACTIVE,
            attempts = task.attempts + 1,
            leaseOwner = workerId,
            leaseUntilMillis = nowMillis + leaseDurationMillis,
            updatedAtMillis = nowMillis
        )
        mutableSnapshot.value = snapshot.copy(
            providerTasks = snapshot.providerTasks.toMutableList().apply {
                this[taskIndex] = claimed
            }
        )
        return claimed
    }

    // 完成或阻断指定 Provider 任务。
    override fun completeProviderTask(
        taskId: String,
        status: ProviderTaskStatus,
        evidence: MapOperationEvidence?,
        errorMessage: String,
        nowMillis: Long
    ): Boolean {
        val snapshot = mutableSnapshot.value
        val taskIndex = snapshot.providerTasks.indexOfFirst { task ->
            task.taskId == taskId
        }
        if (taskIndex < 0) {
            return false
        }
        val task = snapshot.providerTasks[taskIndex]
        val completed = task.copy(
            status = status,
            updatedAtMillis = nowMillis,
            leaseOwner = "",
            leaseUntilMillis = 0L,
            lastError = errorMessage,
            evidence = evidence
        )
        mutableSnapshot.value = snapshot.copy(
            providerTasks = snapshot.providerTasks.toMutableList().apply {
                this[taskIndex] = completed
            },
            providerCompletedKeys = if (
                status == ProviderTaskStatus.COMPLETED ||
                status == ProviderTaskStatus.SKIPPED
            ) {
                snapshot.providerCompletedKeys + completed.candidateKey
            } else {
                snapshot.providerCompletedKeys
            },
            records = if (status == ProviderTaskStatus.COMPLETED) {
                snapshot.records + CheckInRecord(
                    id = "provider-$taskId",
                    poiUid = completed.candidateKey.serialized(),
                    poiName = completed.candidate.name,
                    address = completed.candidate.address,
                    category = completed.provider.name,
                    latitude = completed.candidate.latitude ?: 0.0,
                    longitude = completed.candidate.longitude ?: 0.0,
                    distanceMeters = 0,
                    stayDurationMillis = 0L,
                    checkInTimeMillis = nowMillis,
                    provider = completed.provider,
                    evidenceSummary = evidence?.message.orEmpty()
                )
            } else {
                snapshot.records
            }
        )
        return true
    }

    // 写入 Provider 永久完成键并关闭同键任务。
    override fun markProviderCompleted(
        key: ProviderPlaceKey,
        evidence: MapOperationEvidence?,
        nowMillis: Long
    ): Boolean {
        val snapshot = mutableSnapshot.value
        if (key in snapshot.providerCompletedKeys) {
            return false
        }
        mutableSnapshot.value = snapshot.copy(
            providerCompletedKeys = snapshot.providerCompletedKeys + key,
            providerTasks = snapshot.providerTasks.map { task ->
                if (
                    task.candidateKey == key &&
                    task.status !in TERMINAL_PROVIDER_STATUSES
                ) {
                    task.copy(
                        status = ProviderTaskStatus.COMPLETED,
                        updatedAtMillis = nowMillis,
                        evidence = evidence
                    )
                } else {
                    task
                }
            }
        )
        return true
    }

    // 更新内存诊断状态。
    override fun updateDiagnostics(
        diagnostics: AccessibilityDiagnostics
    ) {
        updateSnapshot { snapshot ->
            snapshot.copy(diagnostics = diagnostics)
        }
    }

    // 更新内存自动化状态并追加日志。
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

    // 保存待消费的分屏请求。
    override fun requestAutoSplitLaunch(requestedAtMillis: Long) {
        updateSnapshot { snapshot ->
            snapshot.copy(autoSplitRequestedAtMillis = requestedAtMillis)
        }
    }

    // 消费并清除当前分屏请求。
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

    // 清除尚未消费的分屏请求。
    override fun clearPendingAutoSplitLaunchRequest() {
        updateSnapshot { snapshot ->
            snapshot.copy(autoSplitRequestedAtMillis = 0L)
        }
    }

    // 清空当前运行日志。
    override fun clearRuntimeLogs() {
        updateSnapshot { snapshot ->
            snapshot.copy(runtimeLogs = emptyList())
        }
    }

    // 重置完整内存快照。
    override fun clearAll(): Boolean {
        mutableSnapshot.value = AutomationSnapshot()
        return true
    }

    private companion object {
        val TERMINAL_PROVIDER_STATUSES = setOf(
            ProviderTaskStatus.COMPLETED,
            ProviderTaskStatus.SKIPPED,
            ProviderTaskStatus.FAILED,
            ProviderTaskStatus.CANCELLED
        )
    }
}
