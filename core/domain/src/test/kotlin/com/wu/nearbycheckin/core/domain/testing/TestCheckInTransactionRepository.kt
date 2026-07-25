// @Time: 2026/07/26 02:49
// @Author: MrWu
// @File: TestCheckInTransactionRepository.kt
// @Software: Android Studio


package com.wu.nearbycheckin.core.domain.testing


import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

import com.wu.nearbycheckin.core.domain.repository.CheckInTransactionRepository
import com.wu.nearbycheckin.core.model.AccessibilityDiagnostics
import com.wu.nearbycheckin.core.model.AutomationLogEntry
import com.wu.nearbycheckin.core.model.AutomationLogLevel
import com.wu.nearbycheckin.core.model.AutomationSnapshot
import com.wu.nearbycheckin.core.model.MapCommand
import com.wu.nearbycheckin.core.model.MapCommandStatus


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

    // 领取第一条已经到期的待执行命令。
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
}
