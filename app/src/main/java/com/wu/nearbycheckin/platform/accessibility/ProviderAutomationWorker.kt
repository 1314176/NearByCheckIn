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
import com.wu.nearbycheckin.core.domain.plugin.PluginPageState
import com.wu.nearbycheckin.core.model.AutomationSessionStatus
import com.wu.nearbycheckin.core.model.MapOperationEvidence
import com.wu.nearbycheckin.core.model.MapOperationStatus
import com.wu.nearbycheckin.core.model.MapProvider
import com.wu.nearbycheckin.core.model.ProviderCheckInTask
import com.wu.nearbycheckin.core.model.ProviderPlaceCandidate
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
    private var amapSecondTapSubmittedTaskId = ""
    private var amapTransientRateLimitMessage = ""
    private var amapTransientRateLimitAtMillis = 0L
    private var pendingNavigationSourcePage: PluginPage? = null
    private var pendingNavigationCommandType: PluginCommandType? = null
    private var navigationDeadlineMillis = 0L
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
        if (provider == MapProvider.BAIDU) {
            coordinator.refreshBaiduDailyQuota()
        }
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
        clearPendingNavigation()
        mainHandler.removeCallbacks(tickRunnable)
    }

    // 保存高德短暂频率提示，供二次点击后的结果验证消费。
    fun recordAmapRateLimitEvidence(message: String) {
        if (provider != MapProvider.AMAP || message.isBlank()) {
            return
        }
        amapTransientRateLimitMessage = message.trim()
        amapTransientRateLimitAtMillis = System.currentTimeMillis()
    }

    // 选择当前阶段可执行的 Provider 并推进一个原子步骤。
    private fun tick() {
        if (
            provider == MapProvider.BAIDU &&
            coordinator.refreshBaiduDailyQuota()
        ) {
            reportStatus("百度地图今日额度已用尽，等待次日自动恢复")
            return
        }
        val initialSnapshot = repository.currentSnapshot()
        if (initialSnapshot.session?.status != AutomationSessionStatus.RUNNING) {
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
        if (waitForNavigationSettlement(pageState.page)) {
            return
        }
        val visibleCandidates = if (
            pageState.page == PluginPage.CANDIDATE_LIST ||
            pageState.page == PluginPage.CHECK_IN_ENTRY
        ) {
            plugin.readCandidates(window)
        } else {
            emptyList()
        }
        if (pageState.page == PluginPage.CANDIDATE_LIST) {
            discardAmapEntryPlaceholders(visibleCandidates)
        }
        val snapshot = repository.currentSnapshot()
        val activeTask = snapshot.providerTasks.firstOrNull { task ->
            task.provider == provider &&
                    task.status in ACTIVE_STATUSES
        }
        if (pageState.page == PluginPage.DAILY_QUOTA_EXHAUSTED) {
            finishBaiduDailyQuota(activeTask, plugin, window)
            return
        }
        if (activeTask?.status == ProviderTaskStatus.VERIFYING) {
            verifyActiveTask(activeTask, plugin.verifyResult(window), rootNode)
            return
        }
        when (pageState.page) {
            PluginPage.DAILY_QUOTA_EXHAUSTED -> Unit
            PluginPage.BLOCKED -> finishBlockedTask(activeTask, plugin, window)
            PluginPage.CANDIDATE_LIST -> processCandidateList(
                provider = provider,
                rootNode = rootNode,
                plugin = plugin,
                windowFingerprint = pageState.fingerprint,
                candidates = visibleCandidates,
                resetScrollHistory = when (provider) {
                    MapProvider.BAIDU ->
                        snapshot.providerRuntime.baiduListStale
                    MapProvider.AMAP ->
                        snapshot.providerRuntime.amapListStale
                }
            )
            PluginPage.CHECK_IN_ENTRY -> if (
                provider == MapProvider.AMAP &&
                visibleCandidates.isNotEmpty()
            ) {
                processCandidateList(
                    provider = provider,
                    rootNode = rootNode,
                    plugin = plugin,
                    windowFingerprint = pageState.fingerprint,
                    candidates = visibleCandidates,
                    resetScrollHistory = snapshot.providerRuntime.amapListStale
                )
            } else {
                executeNavigation(
                    rootNode = rootNode,
                    packageName = packageName,
                    command = plugin.buildNextAction(pageState),
                    sourcePage = pageState.page
                )
            }
            else -> executeNavigation(
                rootNode = rootNode,
                packageName = packageName,
                command = plugin.buildNextAction(pageState),
                sourcePage = pageState.page
            )
        }
    }

    // 持久化百度每日额度证据并立即暂停当天百度队列。
    private fun finishBaiduDailyQuota(
        task: ProviderCheckInTask?,
        plugin: com.wu.nearbycheckin.core.domain.plugin.MapAutomationPlugin,
        window: com.wu.nearbycheckin.core.domain.plugin.MapWindowSnapshot
    ) {
        val observedEvidence = plugin.verifyResult(window)
        val evidence = observedEvidence.copy(
            taskId = task?.taskId.orEmpty(),
            commandId = task?.commandId.orEmpty()
                .ifBlank { observedEvidence.commandId }
        )
        if (task != null) {
            coordinator.finishTask(task.taskId, evidence)
        }
        coordinator.markBaiduDailyQuotaReached(evidence)
        reportStatus(evidence.message)
    }

    // 进入真实地址列表后取消旧入口页生成的临时候选，避免它们抢占地点任务。
    private fun discardAmapEntryPlaceholders(
        visibleCandidates: List<ProviderPlaceCandidate>
    ) {
        if (
            provider != MapProvider.AMAP ||
            !AmapCandidateTransitionPolicy.hasRealCandidate(visibleCandidates)
        ) {
            return
        }
        val obsoleteTasks = repository.currentSnapshot().providerTasks
            .filter { task ->
                task.provider == MapProvider.AMAP &&
                        task.status !in TERMINAL_TASK_STATUSES &&
                        AmapCandidateTransitionPolicy.isEntryPlaceholder(
                            task.candidate
                        )
            }
        if (obsoleteTasks.isEmpty()) {
            return
        }
        val nowMillis = System.currentTimeMillis()
        obsoleteTasks.forEach { task ->
            repository.completeProviderTask(
                taskId = task.taskId,
                status = ProviderTaskStatus.CANCELLED,
                evidence = null,
                errorMessage = "已进入高德真实候选列表",
                nowMillis = nowMillis
            )
        }
        verificationDeadlineMillis = 0L
        clearAmapFollowUpState()
        reportStatus("高德地图已识别真实地点列表，开始逐项打卡")
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
        val needsFreshLocation = provider == MapProvider.BAIDU ||
                candidates.any { candidate ->
                    candidate.stableHint.startsWith(
                        DEVICE_LOCATION_HINT_PREFIX
                    )
                }
        if (
            needsFreshLocation &&
            !locationFreshnessPolicy.isFresh(latestLocation, nowMillis)
        ) {
            reportStatus(
                "${provider.displayName()}等待误差小于500米且30秒内的新定位"
            )
            return
        }
        coordinator.beginCandidateRefresh(provider)
        val locationBoundCandidates = candidates.map { candidate ->
            if (
                latestLocation == null ||
                !candidate.stableHint.startsWith(
                    DEVICE_LOCATION_HINT_PREFIX
                )
            ) {
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
        if (!gestureDispatcher.canExecuteCommand(provider)) {
            return
        }
        val task = coordinator.claimNextTask(provider, WORKER_ID)
        if (task == null) {
            scrollCandidatesIfNeeded(
                rootNode = rootNode,
                provider = provider,
                fingerprint = windowFingerprint,
                hasVisibleCandidates = candidates.isNotEmpty()
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
            if (command.repeatAfterMillis > 0L) {
                prepareAmapRepeatVerification(
                    taskId = task.taskId,
                    command = command,
                    result = result
                )
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
        val transientRateLimitMessage = amapTransientRateLimitMessage
            .takeIf {
                System.currentTimeMillis() -
                        amapTransientRateLimitAtMillis <=
                        AMAP_TRANSIENT_EVIDENCE_MAX_AGE_MILLIS
            }.orEmpty()
        val resolvedEvidence = if (task.provider == MapProvider.AMAP) {
            AmapCheckInFollowUpPolicy.resolveProviderEvidence(
                evidence = evidence,
                secondClickSubmitted =
                    amapSecondTapSubmittedTaskId == task.taskId,
                transientMessage = transientRateLimitMessage
            )
        } else {
            evidence
        }
        val normalizedEvidence = resolvedEvidence.copy(
            taskId = task.taskId,
            commandId = task.commandId.ifBlank {
                resolvedEvidence.commandId
            }
        )
        if (resolvedEvidence.resultStatus == MapOperationStatus.UNKNOWN) {
            if (
                task.provider == MapProvider.AMAP &&
                resolvedEvidence.page == PluginPage.CHECK_IN_ENTRY.name &&
                amapSecondTapTaskId != task.taskId &&
                amapSecondTapSubmittedTaskId != task.taskId &&
                submitAmapCheckInFromEntry(task, rootNode)
            ) {
                return
            }
            if (
                task.provider == MapProvider.AMAP &&
                amapSecondTapTaskId == task.taskId &&
                System.currentTimeMillis() >= amapSecondTapDueMillis
            ) {
                val command = buildAmapRepeatCommand(task)
                if (command != null) {
                    val result = executeCommand(
                        rootNode,
                        MapProvider.AMAP.packageName(),
                        command
                    )
                    if (result.success) {
                        amapSecondTapTaskId = ""
                        amapSecondTapDueMillis = 0L
                        amapSecondTapSubmittedTaskId = task.taskId
                        verificationDeadlineMillis =
                            System.currentTimeMillis() +
                                    command.timeoutMillis.coerceAtLeast(
                                        VERIFY_RECOVERY_MILLIS
                                    )
                        reportStatus(
                            "高德地图已执行第二次打卡，等待地点统计增长或明确成功页"
                        )
                        return
                    }
                    reportStatus("高德地图第二次打卡未执行：${result.message}")
                }
                if (System.currentTimeMillis() < verificationDeadlineMillis) {
                    return
                }
            }
            if (verificationDeadlineMillis == 0L) {
                verificationDeadlineMillis =
                    System.currentTimeMillis() + VERIFY_RECOVERY_MILLIS
            }
            if (System.currentTimeMillis() < verificationDeadlineMillis) {
                return
            }
            val timeoutMessage = if (
                normalizedEvidence.message.contains("频率提示")
            ) {
                "${normalizedEvidence.message}；最终证据验证超时，准备重试"
            } else {
                "${task.provider.displayName()}结果验证超时，准备重试"
            }
            coordinator.finishTask(
                task.taskId,
                normalizedEvidence.copy(
                    resultStatus = MapOperationStatus.TIMEOUT,
                    message = timeoutMessage
                )
            )
            reportStatus(timeoutMessage)
            verificationDeadlineMillis = 0L
            clearAmapFollowUpState()
            return
        }
        coordinator.finishTask(task.taskId, normalizedEvidence)
        verificationDeadlineMillis = 0L
        clearAmapFollowUpState()
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
            rootNode = rootNode,
            packageName = task.provider.packageName(),
            command = dismissCommand,
            sourcePage = if (
                normalizedEvidence.resultStatus ==
                MapOperationStatus.SUCCESS
            ) PluginPage.SUCCESS else PluginPage.ALREADY_COMPLETED
        )
        coordinator.markListStale(task.provider)
    }

    // 在真实地点选中后提交“开始打卡”，并安排两秒后的确认点击。
    private fun submitAmapCheckInFromEntry(
        task: ProviderCheckInTask,
        rootNode: AccessibilityNodeInfo
    ): Boolean {
        val plugin = registry.resolve(MapProvider.AMAP) ?: return false
        val command = plugin.buildNextAction(
            PluginPageState(
                page = PluginPage.CHECK_IN_ENTRY,
                profileVersion = plugin.profileVersion,
                fingerprint = task.candidate.fingerprint,
                evidence = task.candidate.name
            )
        )?.copy(taskId = task.taskId) ?: return false
        val result = executeCommand(
            rootNode,
            MapProvider.AMAP.packageName(),
            command
        )
        if (!result.success) {
            reportStatus(result.message)
            return false
        }
        prepareAmapRepeatVerification(
            taskId = task.taskId,
            command = command,
            result = result
        )
        return true
    }

    // 首次真实手势完成后等待页面稳定，再安排重新定位的第二次点击。
    private fun prepareAmapRepeatVerification(
        taskId: String,
        command: PluginCommand,
        result: PluginCommandExecutionResult
    ) {
        amapTransientRateLimitMessage = ""
        amapTransientRateLimitAtMillis = 0L
        amapSecondTapSubmittedTaskId = ""
        if (result.deferredRepeatSubmitted) {
            amapSecondTapTaskId = taskId
            amapSecondTapDueMillis = Long.MAX_VALUE
            verificationDeadlineMillis = System.currentTimeMillis() +
                    AMAP_FIRST_TAP_COMPLETION_TIMEOUT_MILLIS
            reportStatus("高德地图首次打卡已进入队列，等待系统完成回调")
            return
        }
        amapSecondTapTaskId = taskId
        amapSecondTapDueMillis = System.currentTimeMillis() +
                command.repeatAfterMillis.coerceAtLeast(
                    AMAP_SECOND_TAP_FALLBACK_MILLIS
                )
        verificationDeadlineMillis =
            System.currentTimeMillis() + command.timeoutMillis
        reportStatus("高德地图已提交开始打卡，2秒后执行确认点击")
    }

    // 根据入口占位任务或真实地点任务重新生成相同的高德确认点击命令。
    private fun buildAmapRepeatCommand(
        task: ProviderCheckInTask
    ): PluginCommand? {
        val plugin = registry.resolve(MapProvider.AMAP) ?: return null
        val command = if (
            AmapCandidateTransitionPolicy.isEntryPlaceholder(task.candidate)
        ) {
            plugin.buildSelectAction(task.candidate)
        } else {
            plugin.buildNextAction(
                PluginPageState(
                    page = PluginPage.CHECK_IN_ENTRY,
                    profileVersion = plugin.profileVersion,
                    fingerprint = task.candidate.fingerprint,
                    evidence = task.candidate.name
                )
            )
        }
        return command?.copy(
            taskId = task.taskId,
            repeatAfterMillis = 0L,
            target = command.target.copy(nodeFingerprint = "")
        )
    }

    // 清除高德当前任务的二次点击和瞬时提示状态。
    private fun clearAmapFollowUpState() {
        amapSecondTapTaskId = ""
        amapSecondTapDueMillis = 0L
        amapSecondTapSubmittedTaskId = ""
        amapTransientRateLimitMessage = ""
        amapTransientRateLimitAtMillis = 0L
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
        fingerprint: String,
        hasVisibleCandidates: Boolean
    ) {
        if (
            fingerprint in visitedListFingerprints ||
            visitedListFingerprints.size >= MAX_CANDIDATE_SCROLLS
        ) {
            reportStatus(
                if (hasVisibleCandidates) {
                    "${provider.displayName()}当前列表已处理完成"
                } else {
                    "${provider.displayName()}候选列表可见但暂未解析到地点，等待重试"
                }
            )
            return
        }
        val result = executeCommand(
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
        if (result.success) {
            visitedListFingerprints += fingerprint
        } else {
            reportStatus(
                if (hasVisibleCandidates) {
                    "${provider.displayName()}当前列表已处理完成"
                } else {
                    "${provider.displayName()}候选列表解析失败：${result.message}"
                }
            )
        }
    }

    // 执行页面导航命令并记录诊断。
    private fun executeNavigation(
        rootNode: AccessibilityNodeInfo,
        packageName: String,
        command: PluginCommand?,
        sourcePage: PluginPage
    ) {
        if (command == null || command.type == PluginCommandType.BLOCK) {
            return
        }
        val result = executeCommand(rootNode, packageName, command)
        if (result.success && command.type in NAVIGATION_COMMAND_TYPES) {
            pendingNavigationSourcePage = sourcePage
            pendingNavigationCommandType = command.type
            navigationDeadlineMillis =
                System.currentTimeMillis() + NAVIGATION_SETTLE_MILLIS
            reportStatus(
                "${provider.displayName()}已提交${command.type.name}，等待页面变化"
            )
        } else {
            reportStatus(result.message)
        }
    }

    // 页面未变化时等待界面稳定，超时后清除假成功并允许重新执行导航。
    private fun waitForNavigationSettlement(
        currentPage: PluginPage
    ): Boolean {
        val sourcePage = pendingNavigationSourcePage ?: return false
        if (currentPage != sourcePage) {
            clearPendingNavigation()
            return false
        }
        if (System.currentTimeMillis() < navigationDeadlineMillis) {
            return true
        }
        reportStatus(
            "${provider.displayName()}${pendingNavigationCommandType?.name.orEmpty()}" +
                    "后页面未变化，准备重试"
        )
        clearPendingNavigation()
        return false
    }

    // 清除已经完成或超时的页面导航等待状态。
    private fun clearPendingNavigation() {
        pendingNavigationSourcePage = null
        pendingNavigationCommandType = null
        navigationDeadlineMillis = 0L
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
            commandExecutor.execute(
                rootNode = rootNode,
                packageName = packageName,
                command = command,
                onDeferredRepeatFirstTapCompleted = { completed ->
                    onAmapFirstTapCompleted(
                        taskId = command.taskId,
                        timeoutMillis = command.timeoutMillis,
                        repeatAfterMillis = command.repeatAfterMillis,
                        completed = completed
                    )
                }
            )
        } finally {
            commandExecuting = false
        }
    }

    // 首次手势完成后开始两秒计时，届时重新读取高德页面并定位当前打卡按钮。
    private fun onAmapFirstTapCompleted(
        taskId: String,
        timeoutMillis: Long,
        repeatAfterMillis: Long,
        completed: Boolean
    ) {
        if (
            provider != MapProvider.AMAP ||
            taskId.isBlank() ||
            repository.currentSnapshot().providerTasks.none { task ->
                task.taskId == taskId &&
                        task.status == ProviderTaskStatus.VERIFYING
            }
        ) {
            return
        }
        val nowMillis = System.currentTimeMillis()
        amapSecondTapTaskId = if (completed) taskId else ""
        amapSecondTapDueMillis = if (completed) {
            nowMillis + repeatAfterMillis.coerceAtLeast(
                AMAP_SECOND_TAP_FALLBACK_MILLIS
            )
        } else {
            0L
        }
        amapSecondTapSubmittedTaskId = if (completed) "" else taskId
        verificationDeadlineMillis = nowMillis +
                repeatAfterMillis.coerceAtLeast(
                    AMAP_SECOND_TAP_FALLBACK_MILLIS
                ) + timeoutMillis.coerceAtLeast(VERIFY_RECOVERY_MILLIS)
        reportStatus(
            if (completed) {
                "高德地图首次打卡已完成，2秒后重新读取页面并点击打卡"
            } else {
                "高德地图首次打卡被系统取消，等待超时后重试"
            }
        )
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
        const val AMAP_SECOND_TAP_FALLBACK_MILLIS = 2_000L
        const val AMAP_FIRST_TAP_COMPLETION_TIMEOUT_MILLIS = 30_000L
        const val AMAP_TRANSIENT_EVIDENCE_MAX_AGE_MILLIS = 10_000L
        const val NAVIGATION_SETTLE_MILLIS = 1_500L
        const val STATUS_REPEAT_INTERVAL_MILLIS = 30_000L
        const val MAX_CANDIDATE_SCROLLS = 5
        const val DEVICE_LOCATION_HINT_PREFIX = "device-real-location"
        val NAVIGATION_COMMAND_TYPES = setOf(
            PluginCommandType.NAVIGATE_MINE,
            PluginCommandType.NAVIGATE_FOOTPRINT,
            PluginCommandType.OPEN_CHECK_IN_ENTRY,
            PluginCommandType.DISMISS_RESULT
        )
        val ACTIVE_STATUSES = setOf(
            ProviderTaskStatus.ACTIVE,
            ProviderTaskStatus.VERIFYING
        )
        val TERMINAL_TASK_STATUSES = setOf(
            ProviderTaskStatus.BLOCKED,
            ProviderTaskStatus.COMPLETED,
            ProviderTaskStatus.SKIPPED,
            ProviderTaskStatus.FAILED,
            ProviderTaskStatus.CANCELLED
        )
    }
}

internal object AmapCandidateTransitionPolicy {

    // 判断真实地址列表是否至少包含一个可执行地点。
    fun hasRealCandidate(candidates: List<ProviderPlaceCandidate>): Boolean {
        return candidates.any { candidate -> !isEntryPlaceholder(candidate) }
    }

    // 识别入口页的设备位置槽位和历史误识别“当前位置”任务。
    fun isEntryPlaceholder(candidate: ProviderPlaceCandidate): Boolean {
        return candidate.stableHint.startsWith(DEVICE_LOCATION_HINT) ||
                candidate.name.trim().trimEnd(':', '：') == CURRENT_LOCATION
    }

    private const val DEVICE_LOCATION_HINT = "device-real-location"
    private const val CURRENT_LOCATION = "当前位置"
}
