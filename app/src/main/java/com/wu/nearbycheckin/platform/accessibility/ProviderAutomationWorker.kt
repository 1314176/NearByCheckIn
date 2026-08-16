// @Time: 2026/08/16 02:16
// @Author: MrWu
// @File: ProviderAutomationWorker.kt
// @Software: Android Studio


package com.wu.nearbycheckin.platform.accessibility


import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

import com.wu.nearbycheckin.core.domain.coordinator.ProviderAutomationCoordinator
import com.wu.nearbycheckin.core.domain.coordinator.ProviderLocationFreshnessPolicy
import com.wu.nearbycheckin.core.domain.coordinator.ProviderSelectionRateLimiter
import com.wu.nearbycheckin.core.domain.plugin.MapAutomationPluginRegistry
import com.wu.nearbycheckin.core.domain.plugin.PluginCommand
import com.wu.nearbycheckin.core.domain.plugin.PluginCommandType
import com.wu.nearbycheckin.core.domain.plugin.PluginPage
import com.wu.nearbycheckin.core.model.AutomationSessionStatus
import com.wu.nearbycheckin.core.model.MapOperationEvidence
import com.wu.nearbycheckin.core.model.MapOperationStatus
import com.wu.nearbycheckin.core.model.MapProvider
import com.wu.nearbycheckin.core.model.ProviderCheckInTask
import com.wu.nearbycheckin.core.model.ProviderTaskStatus
import com.wu.nearbycheckin.core.domain.repository.CheckInTransactionRepository
import com.wu.nearbycheckin.platform.maps.PluginWindowSnapshotCollector


/**
 * 独立运行一个 Provider 队列并通过共享仲裁器串行执行无障碍命令。
 */
class ProviderAutomationWorker(
    private val service: AccessibilityService,
    private val repository: CheckInTransactionRepository,
    private val coordinator: ProviderAutomationCoordinator,
    private val registry: MapAutomationPluginRegistry,
    private val provider: MapProvider,
    private val gestureDispatcher: AccessibilityGestureTapDispatcher,
    private val onStatusChanged: (String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val commandExecutor = PluginCommandExecutor(gestureDispatcher)
    private val locationFreshnessPolicy = ProviderLocationFreshnessPolicy()
    private val selectionRateLimiter = ProviderSelectionRateLimiter()
    private var running = false
    private var commandExecuting = false
    private var verificationDeadlineMillis = 0L
    private val visitedListFingerprints = linkedSetOf<String>()
    private var amapSecondTapTaskId = ""
    private var amapSecondTapDueMillis = 0L
    private var lastReportedStatus = ""
    private var lastReportedAtMillis = 0L

    val isBusy: Boolean
        get() = commandExecuting ||
                repository.currentSnapshot().providerTasks.any { task ->
                    task.provider == provider &&
                            (
                                task.status == ProviderTaskStatus.ACTIVE ||
                                task.status == ProviderTaskStatus.VERIFYING
                            )
                }

    private val tickRunnable = object : Runnable {

        // 执行一次 Provider 调度并安排下一次轮询。
        override fun run() {
            if (!running) {
                return
            }
            runCatching(::tick).onFailure { error ->
                Log.e(TAG, "Provider 自动化轮询异常", error)
                reportStatus("Provider 自动化异常：${error.message}")
            }
            scheduleNext(POLL_INTERVAL_MILLIS)
        }
    }

    // 启动并恢复持久化任务租约。
    fun start(initialDelayMillis: Long = 0L) {
        if (running) {
            return
        }
        running = true
        coordinator.resumePendingTasks(provider)
        mainHandler.postDelayed(
            tickRunnable,
            initialDelayMillis.coerceAtLeast(0L)
        )
    }

    // 停止轮询且不丢弃持久化任务。
    fun stop() {
        running = false
        commandExecuting = false
        mainHandler.removeCallbacks(tickRunnable)
    }

    // 选择当前阶段可执行的 Provider 并推进一个原子步骤。
    private fun tick() {
        val snapshot = repository.currentSnapshot()
        if (snapshot.session?.status != AutomationSessionStatus.RUNNING) {
            return
        }
        val plugin = registry.resolve(provider) ?: return
        val packageName = provider.packageName()
        val rootNode = findRootNode(packageName) ?: run {
            reportStatus("${provider.displayName()}窗口暂不可见，后台任务保持等待")
            return
        }
        val window = PluginWindowSnapshotCollector.collect(
            rootNode = rootNode,
            provider = provider,
            packageName = packageName,
            appVersion = packageVersion(packageName),
            profileVersion = plugin.profileVersion
        )
        val pageState = plugin.inspect(window)
        coordinator.updatePluginDiagnostics(
            provider = provider,
            page = pageState.page.name,
            action = "INSPECT",
            evidence = pageState.evidence
        )
        val activeTask = snapshot.providerTasks.firstOrNull { task ->
            task.provider == provider &&
                    task.status in ACTIVE_STATUSES
        }
        if (activeTask?.status == ProviderTaskStatus.VERIFYING) {
            verifyActiveTask(activeTask, plugin.verifyResult(window), rootNode)
            return
        }
        when (pageState.page) {
            PluginPage.BLOCKED -> finishBlockedTask(activeTask, plugin, window)
            PluginPage.CANDIDATE_LIST -> processCandidateList(
                provider = provider,
                rootNode = rootNode,
                plugin = plugin,
                windowFingerprint = pageState.fingerprint,
                candidates = plugin.readCandidates(window),
                resetScrollHistory = when (provider) {
                    MapProvider.BAIDU ->
                        snapshot.providerRuntime.baiduListStale
                    MapProvider.AMAP ->
                        snapshot.providerRuntime.amapListStale
                }
            )
            else -> executeNavigation(
                rootNode = rootNode,
                packageName = packageName,
                command = plugin.buildNextAction(pageState)
            )
        }
    }

    // 用 UI 列表原子刷新队列并选择首个未永久完成候选。
    private fun processCandidateList(
        provider: MapProvider,
        rootNode: AccessibilityNodeInfo,
        plugin: com.wu.nearbycheckin.core.domain.plugin.MapAutomationPlugin,
        windowFingerprint: String,
        candidates: List<com.wu.nearbycheckin.core.model.ProviderPlaceCandidate>,
        resetScrollHistory: Boolean
    ) {
        if (resetScrollHistory) {
            visitedListFingerprints.clear()
        }
        val nowMillis = System.currentTimeMillis()
        val latestLocation = repository.currentSnapshot()
            .providerRuntime.latestLocation
        if (
            provider == MapProvider.BAIDU &&
            !locationFreshnessPolicy.isFresh(latestLocation, nowMillis)
        ) {
            reportStatus("百度地图等待误差小于500米且30秒内的新定位")
            return
        }
        coordinator.beginCandidateRefresh(provider)
        val locationBoundCandidates = candidates.map { candidate ->
            if (latestLocation == null) {
                candidate
            } else {
                candidate.copy(
                    latitude = candidate.latitude ?: latestLocation.latitude,
                    longitude = candidate.longitude ?: latestLocation.longitude
                )
            }
        }
        coordinator.replaceCandidates(
            provider = provider,
            candidates = locationBoundCandidates,
            listFingerprint = windowFingerprint,
            profileVersion = plugin.profileVersion
        )
        if (
            provider == MapProvider.BAIDU &&
            !selectionRateLimiter.canSelect(nowMillis)
        ) {
            return
        }
        if (!gestureDispatcher.canExecuteCommand()) {
            return
        }
        val task = coordinator.claimNextTask(provider, WORKER_ID)
        if (task == null) {
            scrollCandidatesIfNeeded(
                rootNode = rootNode,
                provider = provider,
                fingerprint = windowFingerprint
            )
            return
        }
        visitedListFingerprints.clear()
        val plannedCommand = plugin.buildSelectAction(task.candidate)
        val command = plannedCommand.copy(
            taskId = task.taskId,
            target = plannedCommand.target.copy(
                candidateKey = task.candidateKey
            )
        )
        val result = executeCommand(rootNode, provider.packageName(), command)
        if (result.success) {
            if (provider == MapProvider.BAIDU) {
                selectionRateLimiter.recordSelection(
                    nowMillis = nowMillis,
                    movementClass = latestLocation?.movementClass
                        ?: com.wu.nearbycheckin.core.model.MovementClass.UNKNOWN
                )
            }
            coordinator.beginVerification(task.taskId, command.commandId)
            verificationDeadlineMillis =
                System.currentTimeMillis() + command.timeoutMillis
            if (provider == MapProvider.AMAP) {
                amapSecondTapTaskId = task.taskId
                amapSecondTapDueMillis =
                    System.currentTimeMillis() + AMAP_SECOND_TAP_DELAY_MILLIS
            }
            reportStatus("${provider.displayName()}正在验证：${task.candidate.name}")
        } else {
            coordinator.finishTask(
                task.taskId,
                failedEvidence(task, command, result.message)
            )
        }
    }

    // 轮询验证确定结果，超时后交给退避策略。
    private fun verifyActiveTask(
        task: ProviderCheckInTask,
        evidence: MapOperationEvidence,
        rootNode: AccessibilityNodeInfo
    ) {
        val normalizedEvidence = evidence.copy(
            taskId = task.taskId,
            commandId = task.commandId.ifBlank { evidence.commandId }
        )
        if (evidence.resultStatus == MapOperationStatus.UNKNOWN) {
            if (
                task.provider == MapProvider.AMAP &&
                amapSecondTapTaskId == task.taskId &&
                System.currentTimeMillis() >= amapSecondTapDueMillis
            ) {
                val command = registry.resolve(MapProvider.AMAP)
                    ?.buildSelectAction(task.candidate)
                    ?.copy(taskId = task.taskId)
                if (command != null) {
                    val result = executeCommand(
                        rootNode,
                        MapProvider.AMAP.packageName(),
                        command
                    )
                    if (result.success) {
                        amapSecondTapTaskId = ""
                        amapSecondTapDueMillis = 0L
                    }
                }
                return
            }
            if (verificationDeadlineMillis == 0L) {
                verificationDeadlineMillis =
                    System.currentTimeMillis() + VERIFY_RECOVERY_MILLIS
            }
            if (System.currentTimeMillis() < verificationDeadlineMillis) {
                return
            }
            coordinator.finishTask(
                task.taskId,
                normalizedEvidence.copy(
                    resultStatus = MapOperationStatus.TIMEOUT,
                    message = "百度地图结果验证超时"
                )
            )
            verificationDeadlineMillis = 0L
            return
        }
        coordinator.finishTask(task.taskId, normalizedEvidence)
        verificationDeadlineMillis = 0L
        amapSecondTapTaskId = ""
        amapSecondTapDueMillis = 0L
        reportStatus(
            "${task.provider.displayName()}${task.candidate.name}：" +
                    normalizedEvidence.message
        )
        val dismissCommand = registry.resolve(task.provider)
            ?.buildNextAction(
                com.wu.nearbycheckin.core.domain.plugin.PluginPageState(
                    page = if (
                        normalizedEvidence.resultStatus ==
                        MapOperationStatus.SUCCESS
                    ) PluginPage.SUCCESS else PluginPage.ALREADY_COMPLETED,
                    profileVersion = normalizedEvidence.profileVersion,
                    fingerprint = normalizedEvidence.afterFingerprint,
                    evidence = normalizedEvidence.message
                )
            )
        executeNavigation(
            rootNode,
            task.provider.packageName(),
            dismissCommand
        )
        coordinator.markListStale(task.provider)
    }

    // 把阻断页转换为当前任务的持久化证据。
    private fun finishBlockedTask(
        task: ProviderCheckInTask?,
        plugin: com.wu.nearbycheckin.core.domain.plugin.MapAutomationPlugin,
        window: com.wu.nearbycheckin.core.domain.plugin.MapWindowSnapshot
    ) {
        if (task == null) {
            reportStatus("${window.provider.displayName()}流程被阻断")
            return
        }
        coordinator.finishTask(
            task.taskId,
            plugin.verifyResult(window).copy(taskId = task.taskId)
        )
    }

    // 在无候选时执行有限滚动并阻止页面循环。
    private fun scrollCandidatesIfNeeded(
        rootNode: AccessibilityNodeInfo,
        provider: MapProvider,
        fingerprint: String
    ) {
        if (
            fingerprint in visitedListFingerprints ||
            visitedListFingerprints.size >= MAX_CANDIDATE_SCROLLS
        ) {
            reportStatus("${provider.displayName()}当前列表已处理完成")
            return
        }
        visitedListFingerprints += fingerprint
        executeCommand(
            rootNode,
            provider.packageName(),
            PluginCommand(
                commandId = "scroll-${System.currentTimeMillis()}",
                taskId = "",
                provider = provider,
                type = PluginCommandType.SCROLL_CANDIDATES,
                createdAtMillis = System.currentTimeMillis()
            )
        )
    }

    // 执行页面导航命令并记录诊断。
    private fun executeNavigation(
        rootNode: AccessibilityNodeInfo,
        packageName: String,
        command: PluginCommand?
    ) {
        if (command == null || command.type == PluginCommandType.BLOCK) {
            return
        }
        val result = executeCommand(rootNode, packageName, command)
        reportStatus(result.message)
    }

    // 在 Worker 串行锁内执行一条命令。
    private fun executeCommand(
        rootNode: AccessibilityNodeInfo,
        packageName: String,
        command: PluginCommand
    ): PluginCommandExecutionResult {
        if (commandExecuting) {
            return PluginCommandExecutionResult(false, "已有无障碍命令执行中")
        }
        commandExecuting = true
        return try {
            val runtime = repository.currentSnapshot().providerRuntime
            val currentPage = when (command.provider) {
                MapProvider.BAIDU -> runtime.baiduPluginPage
                MapProvider.AMAP -> runtime.amapPluginPage
            }.ifBlank { "UNKNOWN" }
            coordinator.updatePluginDiagnostics(
                provider = command.provider,
                page = currentPage,
                action = command.type.name,
                evidence = command.evidenceHint
            )
            commandExecutor.execute(rootNode, packageName, command)
        } finally {
            commandExecuting = false
        }
    }

    // 生成命令提交失败证据。
    private fun failedEvidence(
        task: ProviderCheckInTask,
        command: PluginCommand,
        message: String
    ): MapOperationEvidence {
        return MapOperationEvidence(
            provider = task.provider,
            taskId = task.taskId,
            commandId = command.commandId,
            profileVersion = registry.resolve(task.provider)
                ?.profileVersion.orEmpty(),
            page = "COMMAND",
            matchedText = task.candidate.name,
            viewId = command.target.nodeFingerprint,
            beforeFingerprint = task.candidate.fingerprint,
            afterFingerprint = "",
            resultStatus = MapOperationStatus.FAILED,
            message = message,
            observedAtMillis = System.currentTimeMillis()
        )
    }

    // 查找指定地图包当前无障碍窗口根节点。
    private fun findRootNode(packageName: String): AccessibilityNodeInfo? {
        return service.windows.orEmpty().firstNotNullOfOrNull { window ->
            window.root?.takeIf { root ->
                root.packageName?.toString() == packageName
            }
        }
    }

    // 读取地图应用版本名称用于诊断而不固定版本。
    private fun packageVersion(packageName: String): String {
        return runCatching {
            service.packageManager.getPackageInfo(packageName, 0)
                .versionName.orEmpty()
        }.getOrDefault("unknown")
    }

    // 覆盖已有轮询延迟避免积累重复任务。
    private fun scheduleNext(delayMillis: Long) {
        mainHandler.removeCallbacks(tickRunnable)
        if (running) {
            mainHandler.postDelayed(tickRunnable, delayMillis)
        }
    }

    // 对相同状态做本 Worker 节流，避免双窗口后台等待刷满日志。
    private fun reportStatus(status: String) {
        val nowMillis = System.currentTimeMillis()
        if (
            status == lastReportedStatus &&
            nowMillis - lastReportedAtMillis < STATUS_REPEAT_INTERVAL_MILLIS
        ) {
            return
        }
        lastReportedStatus = status
        lastReportedAtMillis = nowMillis
        onStatusChanged(status)
    }

    // 返回地图包名。
    private fun MapProvider.packageName(): String {
        return when (this) {
            MapProvider.BAIDU -> BAIDU_PACKAGE_NAME
            MapProvider.AMAP -> AMAP_PACKAGE_NAME
        }
    }

    // 返回中文地图名称。
    private fun MapProvider.displayName(): String {
        return when (this) {
            MapProvider.BAIDU -> "百度地图"
            MapProvider.AMAP -> "高德地图"
        }
    }

    private companion object {
        const val TAG = "ProviderWorker"
        const val WORKER_ID = "provider-accessibility-worker"
        const val BAIDU_PACKAGE_NAME = "com.baidu.BaiduMap"
        const val AMAP_PACKAGE_NAME = "com.autonavi.minimap"
        const val POLL_INTERVAL_MILLIS = 1_000L
        const val VERIFY_RECOVERY_MILLIS = 10_000L
        const val AMAP_SECOND_TAP_DELAY_MILLIS = 2_000L
        const val STATUS_REPEAT_INTERVAL_MILLIS = 30_000L
        const val MAX_CANDIDATE_SCROLLS = 5
        val ACTIVE_STATUSES = setOf(
            ProviderTaskStatus.ACTIVE,
            ProviderTaskStatus.VERIFYING
        )
    }
}
