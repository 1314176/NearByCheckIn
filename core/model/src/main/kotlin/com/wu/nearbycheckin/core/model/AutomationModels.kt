// @Time: 2026/07/20 18:00
// @Author: MrWu
// @File: AutomationModels.kt
// @Software: Android Studio


@file:Suppress("SpellCheckingInspection")


package com.wu.nearbycheckin.core.model


/**
 * 保存等待候选评估的标准化地点信息。
 */
data class NearbyPlaceCandidate(
    val unifiedPlaceId: String,
    val providerPlaceIds: Set<String>,
    val name: String,
    val address: String,
    val category: String,
    val latitude: Double,
    val longitude: Double,
    val distanceMeters: Int,
    val locationAccuracyMeters: Float,
    val speedKmh: Float,
    val locationTimeMillis: Long,
    val firstSeenAtMillis: Long,
    val observedAtMillis: Long,
    val deviceLatitude: Double = 0.0,
    val deviceLongitude: Double = 0.0
)

/**
 * 标识自动化会话当前生命周期状态。
 */
enum class AutomationSessionStatus {
    RUNNING,
    STOPPED
}

/**
 * 保存一次前台巡航自动化会话。
 */
data class AutomationSession(
    val sessionId: String,
    val startedAtMillis: Long,
    val stoppedAtMillis: Long,
    val status: AutomationSessionStatus
)

/**
 * 标识双地图事务的领域状态。
 */
enum class DualMapTransactionStatus {
    QUEUED,
    RUNNING,
    BAIDU_RUNNING,
    AMAP_RUNNING,
    PARTIAL_SUCCESS,
    RETRY_WAIT,
    COMPLETED,
    BLOCKED,
    FAILED,
    CANCELLED
}

/**
 * 保存纯 Kotlin 状态机需要的全部显式状态。
 */
data class DualMapState(
    val status: DualMapTransactionStatus,
    val baiduResult: MapOperationResult? = null,
    val amapResult: MapOperationResult? = null,
    val baiduAttempts: Int = 0,
    val amapAttempts: Int = 0,
    val baiduTerminal: Boolean = false,
    val amapTerminal: Boolean = false,
    val retryProvider: MapProvider? = null,
    val retryAtMillis: Long = 0L,
    val blockedReason: String = ""
)

/**
 * 保存一个地点从排队到双地图终态的完整事务。
 */
data class CheckInTransaction(
    val transactionId: String,
    val sessionId: String,
    val candidate: NearbyPlaceCandidate,
    val state: DualMapState,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

/**
 * 标识地图命令在持久化队列中的领取状态。
 */
enum class MapCommandStatus {
    PENDING,
    CLAIMED,
    COMPLETED
}

/**
 * 保存无障碍 Worker 可原子领取的单端地图命令。
 */
data class MapCommand(
    val commandId: String,
    val transactionId: String,
    val provider: MapProvider,
    val attempt: Int,
    val createdAtMillis: Long,
    val availableAtMillis: Long,
    val status: MapCommandStatus,
    val workerId: String = "",
    val leaseUntilMillis: Long = 0L,
    val completedAtMillis: Long = 0L,
    val result: MapOperationResult? = null
)

/**
 * 保存 UI、通知和悬浮窗共同观察的无障碍诊断状态。
 */
data class AccessibilityDiagnostics(
    val baiduReady: Boolean = false,
    val amapReady: Boolean = false,
    val totalWindowCount: Int = 0,
    val targetWindowCount: Int = 0,
    val targetNodeCount: Int = 0,
    val baiduClickableNodeCount: Int = 0,
    val baiduCandidateSummary: String = "",
    val amapClickableNodeCount: Int = 0,
    val amapCandidateSummary: String = "",
    val reason: String = "",
    val lastUpdatedMillis: Long = 0L
)

/**
 * 标识持久化运行日志的严重级别。
 */
enum class AutomationLogLevel {
    INFO,
    WARNING,
    ERROR
}

/**
 * 保存可在应用内查看的单条结构化运行日志。
 */
data class AutomationLogEntry(
    val timestampMillis: Long,
    val level: AutomationLogLevel,
    val source: String,
    val message: String
)

/**
 * 作为业务状态唯一事实来源保存完整自动化快照。
 */
data class AutomationSnapshot(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val session: AutomationSession? = null,
    val candidates: List<NearbyPlaceCandidate> = emptyList(),
    val transactions: List<CheckInTransaction> = emptyList(),
    val commands: List<MapCommand> = emptyList(),
    val records: List<CheckInRecord> = emptyList(),
    val legacyDualMapRecords: List<DualMapCheckInRecord> = emptyList(),
    val completedPlaceKeys: Set<String> = emptySet(),
    val providerCandidates: List<ProviderPlaceCandidate> = emptyList(),
    val providerTasks: List<ProviderCheckInTask> = emptyList(),
    val providerCompletedKeys: Set<ProviderPlaceKey> = emptySet(),
    val providerRuntime: DualMapRuntimeState = DualMapRuntimeState(),
    val diagnostics: AccessibilityDiagnostics = AccessibilityDiagnostics(),
    val automationStatus: String = "",
    val autoSplitRequestedAtMillis: Long = 0L,
    val runtimeLogs: List<AutomationLogEntry> = emptyList()
) {

    val activeTransaction: CheckInTransaction?
        get() = transactions.firstOrNull { transaction ->
            transaction.state.status in ACTIVE_TRANSACTION_STATUSES
        }

    val queuedTransactions: List<CheckInTransaction>
        get() = transactions
            .filter { transaction ->
                transaction.state.status == DualMapTransactionStatus.QUEUED
            }
            .sortedBy { transaction ->
                transaction.createdAtMillis
            }

    val activeProviderTask: ProviderCheckInTask?
        get() = providerTasks.firstOrNull { task ->
            task.status in ACTIVE_PROVIDER_TASK_STATUSES
        }

    // 返回指定 Provider 按创建时间排列的未终结任务。
    fun providerQueue(provider: MapProvider): List<ProviderCheckInTask> {
        return providerTasks.filter { task ->
            task.provider == provider &&
                    task.status in QUEUED_PROVIDER_TASK_STATUSES
        }.sortedBy(ProviderCheckInTask::createdAtMillis)
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 7

        private val ACTIVE_TRANSACTION_STATUSES = setOf(
            DualMapTransactionStatus.RUNNING,
            DualMapTransactionStatus.BAIDU_RUNNING,
            DualMapTransactionStatus.AMAP_RUNNING,
            DualMapTransactionStatus.PARTIAL_SUCCESS,
            DualMapTransactionStatus.RETRY_WAIT,
            DualMapTransactionStatus.BLOCKED
        )

        private val ACTIVE_PROVIDER_TASK_STATUSES = setOf(
            ProviderTaskStatus.ACTIVE,
            ProviderTaskStatus.VERIFYING,
            ProviderTaskStatus.RETRY_WAIT,
            ProviderTaskStatus.BLOCKED
        )

        private val QUEUED_PROVIDER_TASK_STATUSES = setOf(
            ProviderTaskStatus.QUEUED,
            ProviderTaskStatus.ACTIVE,
            ProviderTaskStatus.VERIFYING,
            ProviderTaskStatus.RETRY_WAIT,
            ProviderTaskStatus.BLOCKED
        )
    }
}
