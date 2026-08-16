// @Time: 2026/07/20 18:05
// @Author: MrWu
// @File: SharedPreferencesCheckInTransactionRepository.kt
// @Software: Android Studio


package com.wu.nearbycheckin.data.repository


import java.io.File

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

import com.wu.nearbycheckin.core.model.AccessibilityDiagnostics
import com.wu.nearbycheckin.core.model.AutomationLogEntry
import com.wu.nearbycheckin.core.model.AutomationLogLevel
import com.wu.nearbycheckin.core.model.AutomationSnapshot
import com.wu.nearbycheckin.core.model.CheckInRecord
import com.wu.nearbycheckin.core.model.DualMapTransactionStatus
import com.wu.nearbycheckin.core.model.MapCommand
import com.wu.nearbycheckin.core.model.MapCommandStatus
import com.wu.nearbycheckin.core.model.MapOperationEvidence
import com.wu.nearbycheckin.core.model.MapProvider
import com.wu.nearbycheckin.core.model.ProviderCheckInTask
import com.wu.nearbycheckin.core.model.ProviderPlaceKey
import com.wu.nearbycheckin.core.model.ProviderTaskStatus
import com.wu.nearbycheckin.core.domain.coordinator.AmapRealLocationDeduplicationPolicy
import com.wu.nearbycheckin.core.domain.repository.CheckInTransactionRepository


/**
 * 使用 SharedPreferences 原子持久化完整自动化快照。
 */
class SharedPreferencesCheckInTransactionRepository(
    context: Context,
    private val legacyMigrator: LegacyCheckInDataMigrator
) : CheckInTransactionRepository {

    private val applicationContext = context.applicationContext
    private val runtimeLogFileStore = AutomationRuntimeLogFileStore(
        File(applicationContext.filesDir, RUNTIME_LOG_DIRECTORY_NAME)
    )
    private val preferences = applicationContext.getSharedPreferences(
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
        archiveLatestRuntimeLog(current, updated)
        mutableSnapshot.value = updated
        return updated
    }

    // 过滤候选扫描产生的高频拒绝状态，避免有用日志被瞬间挤出。
    private fun shouldAutomaticallyLog(statusText: String): Boolean {
        return statusText.isNotBlank() &&
                !statusText.startsWith(CANDIDATE_REJECTED_STATUS_PREFIX)
    }

    // 原子领取指定 Provider 队列中的一个到期命令。
    @Synchronized
    override fun claimNextCommand(
        provider: MapProvider,
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
                    command.provider == provider &&
                    (
                            provider != MapProvider.AMAP ||
                                    AmapRealLocationDeduplicationPolicy
                                        .isLocationLeader(
                                            snapshot = current,
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

    // 原子领取指定 Provider 的第一条可执行地点任务。
    @Synchronized
    override fun claimNextProviderTask(
        provider: MapProvider,
        workerId: String,
        nowMillis: Long,
        leaseDurationMillis: Long
    ): ProviderCheckInTask? {
        val current = mutableSnapshot.value
        val visibleKeys = current.providerCandidates
            .asSequence()
            .filter { candidate -> candidate.provider == provider }
            .map { candidate -> candidate.toPlaceKey() }
            .toSet()
        val taskIndex = current.providerTasks.indexOfFirst { task ->
            task.provider == provider &&
                    task.candidateKey in visibleKeys &&
                    task.candidateKey !in current.providerCompletedKeys &&
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
        val task = current.providerTasks[taskIndex]
        val claimed = task.copy(
            status = ProviderTaskStatus.ACTIVE,
            attempts = task.attempts + 1,
            leaseOwner = workerId,
            leaseUntilMillis = nowMillis + leaseDurationMillis,
            updatedAtMillis = nowMillis,
            lastError = ""
        )
        val updated = current.copy(
            providerTasks = current.providerTasks.toMutableList().apply {
                this[taskIndex] = claimed
            },
            providerRuntime = current.providerRuntime.copy(
                activeProvider = provider
            )
        )
        persistSnapshot(updated)
        mutableSnapshot.value = updated
        return claimed
    }

    // 原子完成或阻断 Provider 地点任务并保存证据。
    @Synchronized
    override fun completeProviderTask(
        taskId: String,
        status: ProviderTaskStatus,
        evidence: MapOperationEvidence?,
        errorMessage: String,
        nowMillis: Long
    ): Boolean {
        val current = mutableSnapshot.value
        val taskIndex = current.providerTasks.indexOfFirst { task ->
            task.taskId == taskId
        }
        if (taskIndex < 0) {
            return false
        }
        val currentTask = current.providerTasks[taskIndex]
        if (currentTask.status in TERMINAL_PROVIDER_TASK_STATUSES) {
            return currentTask.status == status
        }
        val updatedTask = currentTask.copy(
            status = status,
            updatedAtMillis = nowMillis,
            leaseOwner = "",
            leaseUntilMillis = 0L,
            lastError = errorMessage,
            evidence = evidence
        )
        val completedKeys = if (
            status == ProviderTaskStatus.COMPLETED ||
            status == ProviderTaskStatus.SKIPPED
        ) {
            current.providerCompletedKeys + updatedTask.candidateKey
        } else {
            current.providerCompletedKeys
        }
        val updated = current.copy(
            providerTasks = current.providerTasks.toMutableList().apply {
                this[taskIndex] = updatedTask
            },
            providerCompletedKeys = completedKeys,
            records = if (status == ProviderTaskStatus.COMPLETED) {
                current.records + CheckInRecord(
                    id = "provider-$taskId",
                    poiUid = updatedTask.candidateKey.serialized(),
                    poiName = updatedTask.candidate.name,
                    address = updatedTask.candidate.address,
                    category = updatedTask.provider.name,
                    latitude = updatedTask.candidate.latitude ?: 0.0,
                    longitude = updatedTask.candidate.longitude ?: 0.0,
                    distanceMeters = updatedTask.candidate.distanceText
                        .filter(Char::isDigit).toIntOrNull() ?: 0,
                    stayDurationMillis = 0L,
                    checkInTimeMillis = nowMillis,
                    provider = updatedTask.provider,
                    evidenceSummary = evidence?.message.orEmpty()
                )
            } else {
                current.records
            },
            providerRuntime = current.providerRuntime.copy(
                activeProvider = null
            )
        )
        persistSnapshot(updated)
        mutableSnapshot.value = updated
        return true
    }

    // 原子写入永久完成键并幂等关闭所有同键活动任务。
    @Synchronized
    override fun markProviderCompleted(
        key: ProviderPlaceKey,
        evidence: MapOperationEvidence?,
        nowMillis: Long
    ): Boolean {
        val current = mutableSnapshot.value
        val alreadyCompleted = key in current.providerCompletedKeys
        val tasks = current.providerTasks.map { task ->
            if (
                task.candidateKey == key &&
                task.status !in TERMINAL_PROVIDER_TASK_STATUSES
            ) {
                task.copy(
                    status = if (task.candidate.completed) {
                        ProviderTaskStatus.SKIPPED
                    } else {
                        ProviderTaskStatus.COMPLETED
                    },
                    updatedAtMillis = nowMillis,
                    leaseOwner = "",
                    leaseUntilMillis = 0L,
                    evidence = evidence
                )
            } else {
                task
            }
        }
        if (alreadyCompleted && tasks == current.providerTasks) {
            return false
        }
        val updated = current.copy(
            providerCompletedKeys = current.providerCompletedKeys + key,
            providerTasks = tasks
        )
        persistSnapshot(updated)
        mutableSnapshot.value = updated
        return true
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
    @Synchronized
    override fun clearRuntimeLogs() {
        runCatching(runtimeLogFileStore::clear).onFailure { error ->
            Log.e(TAG, "清空完整运行日志文件失败", error)
        }
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
            archiveLatestRuntimeLog(current, emptySnapshot)
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

    // 将本次新增的 UI 日志同步追加到不覆盖历史的完整日志文件。
    private fun archiveLatestRuntimeLog(
        previous: AutomationSnapshot,
        updated: AutomationSnapshot
    ) {
        val latest = updated.runtimeLogs.lastOrNull() ?: return
        if (latest == previous.runtimeLogs.lastOrNull()) {
            return
        }
        runCatching {
            runtimeLogFileStore.append(latest)
        }.onFailure { error ->
            Log.e(TAG, "追加完整运行日志文件失败", error)
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
        const val TAG = "CheckInTransactionRepo"
        const val PREFERENCES_NAME = "check_in_transactions_v4"
        const val RUNTIME_LOG_DIRECTORY_NAME = "automation_logs"
        const val SCHEMA_VERSION_KEY = "schema_version"
        const val SNAPSHOT_JSON_KEY = "automation_snapshot_json"
        const val DEFAULT_LOG_SOURCE = "自动化"
        const val SPLIT_LOG_SOURCE = "分屏启动"
        const val MAX_RUNTIME_LOG_COUNT = 300
        const val MAX_LOG_SOURCE_LENGTH = 32
        const val MAX_LOG_MESSAGE_LENGTH = 500
        const val CANDIDATE_REJECTED_STATUS_PREFIX = "候选已拒绝："

        val EXECUTABLE_STATUSES = setOf(
            DualMapTransactionStatus.RUNNING,
            DualMapTransactionStatus.BAIDU_RUNNING,
            DualMapTransactionStatus.AMAP_RUNNING,
            DualMapTransactionStatus.PARTIAL_SUCCESS,
            DualMapTransactionStatus.RETRY_WAIT,
            DualMapTransactionStatus.BLOCKED
        )

        val TERMINAL_PROVIDER_TASK_STATUSES = setOf(
            ProviderTaskStatus.COMPLETED,
            ProviderTaskStatus.SKIPPED,
            ProviderTaskStatus.FAILED,
            ProviderTaskStatus.CANCELLED
        )
    }
}
