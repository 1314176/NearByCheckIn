// @Time: 2026/07/20 18:05
// @Author: MrWu
// @File: SharedPreferencesCheckInTransactionRepository.kt
// @Software: Android Studio


package com.wu.nearbycheckin.data.repository


import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

import com.wu.nearbycheckin.core.model.AccessibilityDiagnostics
import com.wu.nearbycheckin.core.model.AutomationLogEntry
import com.wu.nearbycheckin.core.model.AutomationLogLevel
import com.wu.nearbycheckin.core.model.AutomationSnapshot
import com.wu.nearbycheckin.core.model.DualMapTransactionStatus
import com.wu.nearbycheckin.core.model.MapCommand
import com.wu.nearbycheckin.core.model.MapCommandStatus
import com.wu.nearbycheckin.core.domain.repository.CheckInTransactionRepository


/**
 * 使用 SharedPreferences 原子持久化完整自动化快照。
 */
class SharedPreferencesCheckInTransactionRepository(
    context: Context,
    private val legacyMigrator: LegacyCheckInDataMigrator
) : CheckInTransactionRepository {

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )
    private val mutableSnapshot = MutableStateFlow(
        loadOrMigrateSnapshot()
    )

    override val automationSnapshot: StateFlow<AutomationSnapshot> =
        mutableSnapshot.asStateFlow()

    // 返回当前已经持久化成功的内存快照。
    @Synchronized
    override fun currentSnapshot(): AutomationSnapshot {
        return mutableSnapshot.value
    }

    // 原子执行快照转换并在提交成功后通知所有观察者。
    @Synchronized
    override fun updateSnapshot(
        transform: (AutomationSnapshot) -> AutomationSnapshot
    ): AutomationSnapshot {
        val current = mutableSnapshot.value
        val transformed = transform(current).copy(
            schemaVersion = AutomationSnapshot.CURRENT_SCHEMA_VERSION
        )
        val updated = if (
            transformed.automationStatus != current.automationStatus &&
            transformed.runtimeLogs == current.runtimeLogs &&
            shouldAutomaticallyLog(transformed.automationStatus)
        ) {
            transformed.withRuntimeLog(
                source = DEFAULT_LOG_SOURCE,
                message = transformed.automationStatus,
                level = AutomationLogLevel.INFO
            )
        } else {
            transformed
        }
        if (updated == current) {
            return current
        }

        persistSnapshot(updated)
        mutableSnapshot.value = updated
        return updated
    }

    // 过滤候选扫描产生的高频拒绝状态，避免有用日志被瞬间挤出。
    private fun shouldAutomaticallyLog(statusText: String): Boolean {
        return statusText.isNotBlank() &&
                !statusText.startsWith(CANDIDATE_REJECTED_STATUS_PREFIX)
    }

    // 原子领取当前活动事务的一个到期命令。
    @Synchronized
    override fun claimNextCommand(
        workerId: String,
        nowMillis: Long,
        leaseDurationMillis: Long
    ): MapCommand? {
        val current = mutableSnapshot.value
        val activeTransactionIds = current.transactions
            .filter { transaction ->
                transaction.state.status in EXECUTABLE_STATUSES
            }
            .mapTo(mutableSetOf()) { transaction ->
                transaction.transactionId
            }
        val commandIndex = current.commands.indexOfFirst { command ->
            command.transactionId in activeTransactionIds &&
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

        val claimedCommand = current.commands[commandIndex].copy(
            status = MapCommandStatus.CLAIMED,
            workerId = workerId,
            leaseUntilMillis = nowMillis + leaseDurationMillis
        )
        val commands = current.commands.toMutableList().apply {
            this[commandIndex] = claimedCommand
        }
        val updated = current.copy(commands = commands)
        persistSnapshot(updated)
        mutableSnapshot.value = updated
        return claimedCommand
    }

    // 原子保存供 UI、通知和悬浮窗共同观察的诊断。
    override fun updateDiagnostics(
        diagnostics: AccessibilityDiagnostics
    ) {
        updateSnapshot { snapshot ->
            snapshot.copy(diagnostics = diagnostics)
        }
    }

    // 原子保存自动化流程的人类可读状态。
    override fun updateAutomationStatus(
        statusText: String,
        source: String,
        level: AutomationLogLevel
    ) {
        updateSnapshot { snapshot ->
            snapshot.copy(
                automationStatus = statusText
            ).withRuntimeLog(
                source = source,
                message = statusText,
                level = level
            )
        }
    }

    // 原子写入新的双地图分屏请求。
    override fun requestAutoSplitLaunch(requestedAtMillis: Long) {
        updateSnapshot { snapshot ->
            snapshot.copy(
                autoSplitRequestedAtMillis = requestedAtMillis,
                automationStatus = "已请求自动打开双地图分屏"
            ).withRuntimeLog(
                source = SPLIT_LOG_SOURCE,
                message = "已请求自动打开双地图分屏",
                level = AutomationLogLevel.INFO
            )
        }
    }

    // 原子取得并清除双地图分屏请求。
    @Synchronized
    override fun consumeAutoSplitLaunchRequest(): Long? {
        val current = mutableSnapshot.value
        val requestedAtMillis = current.autoSplitRequestedAtMillis
        if (requestedAtMillis <= 0L) {
            return null
        }
        val updated = current.copy(
            autoSplitRequestedAtMillis = 0L
        )
        persistSnapshot(updated)
        mutableSnapshot.value = updated
        return requestedAtMillis
    }

    // 原子清除遗留的双地图分屏请求。
    override fun clearPendingAutoSplitLaunchRequest() {
        updateSnapshot { snapshot ->
            snapshot.copy(autoSplitRequestedAtMillis = 0L)
        }
    }

    // 清空应用内持久化运行日志。
    override fun clearRuntimeLogs() {
        updateSnapshot { snapshot ->
            snapshot.copy(runtimeLogs = emptyList())
        }
    }

    // 清空 V4 快照并保留最近诊断以便继续排障。
    @Synchronized
    override fun clearAll(): Boolean {
        val current = mutableSnapshot.value
        val emptySnapshot = AutomationSnapshot(
            diagnostics = current.diagnostics,
            automationStatus = "本地记录与事务已清空",
            runtimeLogs = current.runtimeLogs
        ).withRuntimeLog(
            source = DEFAULT_LOG_SOURCE,
            message = "本地记录与事务已清空",
            level = AutomationLogLevel.INFO
        )
        return try {
            persistSnapshot(emptySnapshot)
            mutableSnapshot.value = emptySnapshot
            true
        } catch (_: IllegalStateException) {
            false
        }
    }

    // 优先恢复 V4 快照，不存在时只执行一次旧数据迁移。
    private fun loadOrMigrateSnapshot(): AutomationSnapshot {
        val storedJson = preferences.getString(
            SNAPSHOT_JSON_KEY,
            null
        )
        if (!storedJson.isNullOrBlank()) {
            AutomationSnapshotJsonCodec.decode(storedJson)?.let {
                return it
            }
        }

        val migrated = legacyMigrator.createInitialSnapshot()
        persistSnapshot(migrated)
        return migrated
    }

    // 同步提交完整 JSON，失败时阻止内存状态前移。
    private fun persistSnapshot(snapshot: AutomationSnapshot) {
        val committed = preferences.edit()
            .putInt(
                SCHEMA_VERSION_KEY,
                AutomationSnapshot.CURRENT_SCHEMA_VERSION
            )
            .putString(
                SNAPSHOT_JSON_KEY,
                AutomationSnapshotJsonCodec.encode(snapshot)
            )
            .commit()
        if (!committed) {
            throw IllegalStateException("自动化快照持久化失败")
        }
    }

    // 向快照追加一条去重、截断并限量保存的结构化日志。
    private fun AutomationSnapshot.withRuntimeLog(
        source: String,
        message: String,
        level: AutomationLogLevel
    ): AutomationSnapshot {
        val normalizedSource = source.trim()
            .ifBlank { DEFAULT_LOG_SOURCE }
            .take(MAX_LOG_SOURCE_LENGTH)
        val normalizedMessage = message.trim()
            .take(MAX_LOG_MESSAGE_LENGTH)
        if (normalizedMessage.isBlank()) {
            return this
        }
        val previous = runtimeLogs.lastOrNull()
        if (
            previous?.source == normalizedSource &&
            previous.level == level &&
            previous.message == normalizedMessage
        ) {
            return this
        }
        val entry = AutomationLogEntry(
            timestampMillis = System.currentTimeMillis(),
            level = level,
            source = normalizedSource,
            message = normalizedMessage
        )
        return copy(
            runtimeLogs = (runtimeLogs + entry)
                .takeLast(MAX_RUNTIME_LOG_COUNT)
        )
    }

    private companion object {
        const val PREFERENCES_NAME = "check_in_transactions_v4"
        const val SCHEMA_VERSION_KEY = "schema_version"
        const val SNAPSHOT_JSON_KEY = "automation_snapshot_json"
        const val DEFAULT_LOG_SOURCE = "自动化"
        const val SPLIT_LOG_SOURCE = "分屏启动"
        const val MAX_RUNTIME_LOG_COUNT = 300
        const val MAX_LOG_SOURCE_LENGTH = 32
        const val MAX_LOG_MESSAGE_LENGTH = 500
        const val CANDIDATE_REJECTED_STATUS_PREFIX = "候选已拒绝："

        val EXECUTABLE_STATUSES = setOf(
            DualMapTransactionStatus.BAIDU_RUNNING,
            DualMapTransactionStatus.AMAP_RUNNING
        )
    }
}
