// @Time: 2026/07/20 18:40
// @Author: MrWu
// @File: DualMapCommandWorker.kt
// @Software: Android Studio


package com.wu.nearbycheckin.platform.accessibility


import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo

import com.wu.nearbycheckin.core.domain.coordinator.CheckInCoordinator
import com.wu.nearbycheckin.core.model.MapCommand
import com.wu.nearbycheckin.core.model.MapOperationResult
import com.wu.nearbycheckin.core.model.MapOperationStatus
import com.wu.nearbycheckin.core.model.MapProvider
import com.wu.nearbycheckin.platform.maps.AmapMapAdapter
import com.wu.nearbycheckin.platform.maps.AmapUiProfile
import com.wu.nearbycheckin.platform.maps.BaiduMapAdapter
import com.wu.nearbycheckin.platform.maps.BaiduUiProfile
import com.wu.nearbycheckin.platform.maps.MapUiAction
import com.wu.nearbycheckin.platform.maps.MapUiDecision
import com.wu.nearbycheckin.platform.maps.MapUiLabelDiagnosticTarget
import com.wu.nearbycheckin.platform.maps.MapUiPage
import com.wu.nearbycheckin.platform.maps.MapUiSnapshot
import com.wu.nearbycheckin.platform.maps.MapUiSnapshotCollector
import com.wu.nearbycheckin.platform.maps.ProviderUiProfile
import com.wu.nearbycheckin.platform.maps.formatMapUiLabelDiagnostics


/**
 * 串行消费单个 Provider 队列、调用 Adapter 并回写结果证据。
 */
class DualMapCommandWorker(
    private val service: AccessibilityService,
    private val coordinator: CheckInCoordinator,
    private val provider: MapProvider,
    private val gestureTapDispatcher: AccessibilityGestureTapDispatcher,
    private val onStatusChanged: (String) -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val resultProbe = MapOperationResultProbe(service)
    private val executionPolicy = MapUiExecutionPolicy()
    private val windowRecoveryPolicy =
        MapWindowRecoveryPolicy()
    private var activeCommand: MapCommand? = null
    private var activeStartedAtMillis = 0L
    private var activeAmapCheckInStage =
        AmapCheckInStage.NONE

    val isBusy: Boolean
        get() = activeCommand != null

    private val pollRunnable = object : Runnable {

        // 定时尝试领取一个到期的持久化命令。
        override fun run() {
            processNextCommand()
            mainHandler.postDelayed(
                this,
                COMMAND_POLL_INTERVAL_MILLIS
            )
        }
    }

    // 启动串行命令轮询。
    fun start() {
        mainHandler.post(pollRunnable)
    }

    // 停止轮询并等待命令租约超时后恢复。
    fun stop() {
        mainHandler.removeCallbacksAndMessages(null)
        activeCommand = null
        activeStartedAtMillis = 0L
        activeAmapCheckInStage = AmapCheckInStage.NONE
    }

    // 在高德首次打卡已提交后把瞬时频率限制提示回写为成功结果。
    fun onAmapRateLimitDetected(message: String) {
        val command = activeCommand ?: return
        if (
            command.provider != MapProvider.AMAP ||
            activeAmapCheckInStage == AmapCheckInStage.NONE
        ) {
            return
        }
        val decision = MapUiDecision(
            page = MapUiPage.RATE_LIMITED,
            action = MapUiAction.WAIT,
            evidence = "打卡提交后触发频率限制",
            reason = "高德打卡已生效"
        )
        val profileEvidence = appendBoundedWorkerEvidence(
            structuredEvidence = buildMapUiEvidence(
                provider = command.provider,
                packageName = AmapUiProfile.packageName,
                appVersion = appVersion(AmapUiProfile.packageName),
                profileVersion = AmapUiProfile.profileVersion,
                page = decision.page,
                action = decision.action,
                evidence = decision.evidence
            ),
            key = "transientMessage",
            value = message
        )
        reportStructuredStatus(
            event = "瞬时结果识别",
            structuredEvidence = profileEvidence
        )
        finishProfileOutcome(
            command = command,
            startedAtMillis = activeStartedAtMillis,
            outcome = MapUiExecutionOutcome.SUCCESS,
            decision = decision,
            profileEvidence = profileEvidence
        )
    }

    // 领取下一个命令并在地图位于后台时保留任务等待窗口恢复。
    private fun processNextCommand() {
        if (activeCommand != null) {
            return
        }

        val command = coordinator.claimNextCommand(
            provider = provider,
            workerId = workerId
        )
            ?: return
        activeCommand = command
        activeStartedAtMillis = System.currentTimeMillis()
        activeAmapCheckInStage = AmapCheckInStage.NONE
        val profile = command.provider.uiProfile()
        reportStructuredStatus(
            event = "命令开始",
            structuredEvidence = buildMapUiEvidence(
                provider = command.provider,
                packageName = profile.packageName,
                appVersion = appVersion(profile.packageName),
                profileVersion = profile.profileVersion,
                page = MapUiPage.UNKNOWN,
                action = MapUiAction.WAIT,
                evidence = "开始执行命令，attempt=${command.attempt}"
            )
        )
        mainHandler.postDelayed(
            {
                executeNextAction(
                    command = command,
                    startedAtMillis = activeStartedAtMillis
                )
            },
            MAP_OPERATION_INITIAL_DELAY_MILLIS
        )
    }

    // 根据当前页面 Profile 决策循环执行一个安全动作。
    private fun executeNextAction(
        command: MapCommand,
        startedAtMillis: Long,
        actionCount: Int = 0,
        lastFingerprint: String = "",
        repeatedUnknownCount: Int = 0,
        windowWaitAttempt: Int = 0,
        amapCheckInStage: AmapCheckInStage =
            AmapCheckInStage.NONE,
        baiduCheckInSubmitted: Boolean = false
    ) {
        if (activeCommand?.commandId != command.commandId) {
            return
        }

        val rootNode = resultProbe.findWindowRootNode(
            command.provider.packageName()
        )
        if (rootNode == null) {
            waitForTargetWindowBeforeAction(
                command = command,
                startedAtMillis = startedAtMillis,
                actionCount = actionCount,
                lastFingerprint = lastFingerprint,
                repeatedUnknownCount = repeatedUnknownCount,
                windowWaitAttempt = windowWaitAttempt,
                amapCheckInStage = amapCheckInStage,
                baiduCheckInSubmitted = baiduCheckInSubmitted
            )
            return
        }

        val profile = command.provider.uiProfile()
        val packageName = profile.packageName
        val currentAppVersion = appVersion(packageName)
        val snapshot = command.provider.captureSnapshot(
            rootNode = rootNode,
            appVersion = currentAppVersion
        )

        val decision = profile.decide(snapshot)
        val fingerprint = snapshot.fingerprint()
        val loopGuardPage = if (
            command.provider == MapProvider.AMAP
        ) {
            AmapCheckInFollowUpPolicy.loopGuardPage(
                page = decision.page,
                action = decision.action,
                stage = amapCheckInStage
            )
        } else {
            decision.page
        }
        val nextRepeatedUnknownCount =
            executionPolicy.repeatedUnknownCountFor(
                page = loopGuardPage,
                fingerprint = fingerprint,
                lastFingerprint = lastFingerprint,
                repeatedUnknownCount = repeatedUnknownCount
            )
        val baseProfileEvidence = buildProfileEvidence(
            provider = command.provider,
            snapshot = snapshot,
            profile = profile,
            decision = decision
        )
        val profileEvidence =
            appendBaiduUnknownDiagnostics(
                command = command,
                rootNode = rootNode,
                decision = decision,
                profileEvidence = baseProfileEvidence
            )
        reportStructuredStatus(
            event = "页面识别",
            structuredEvidence = profileEvidence
        )

        if (
            MapUiSuccessCleanupPolicy.shouldExecute(
                provider = command.provider,
                page = decision.page,
                action = decision.action
            )
        ) {
            executeSuccessPageCleanup(
                command = command,
                startedAtMillis = startedAtMillis,
                rootNode = rootNode,
                decision = decision,
                profileEvidence = profileEvidence,
                fingerprint = fingerprint,
                actionCount = actionCount,
                amapCheckInStage = amapCheckInStage,
                baiduCheckInSubmitted =
                    baiduCheckInSubmitted
            )
            return
        }

        val outcome = executionPolicy.outcomeFor(
            page = decision.page,
            actionCount = actionCount,
            repeatedUnknownCount = nextRepeatedUnknownCount
        )
        if (outcome != MapUiExecutionOutcome.CONTINUE) {
            finishProfileOutcome(
                command = command,
                startedAtMillis = startedAtMillis,
                outcome = outcome,
                decision = decision,
                profileEvidence = profileEvidence
            )
            return
        }

        val action = decision.action
        val amapCheckInAlreadySubmittedTwice =
            command.provider == MapProvider.AMAP &&
                    action == MapUiAction.CLICK_CHECK_IN &&
                    !AmapCheckInFollowUpPolicy
                        .shouldExecuteCheckIn(amapCheckInStage)
        if (
            action == null ||
            action == MapUiAction.WAIT ||
            amapCheckInAlreadySubmittedTwice
        ) {
            reportStructuredStatus(
                event = "动作等待",
                structuredEvidence = profileEvidence
            )
            mainHandler.postDelayed(
                {
                    executeNextAction(
                        command = command,
                        startedAtMillis = startedAtMillis,
                        actionCount = actionCount,
                        lastFingerprint = fingerprint,
                        repeatedUnknownCount =
                            nextRepeatedUnknownCount,
                        windowWaitAttempt = 0,
                        amapCheckInStage = amapCheckInStage,
                        baiduCheckInSubmitted =
                            baiduCheckInSubmitted
                    )
                },
                MAP_OPERATION_STEP_DELAY_MILLIS
            )
            return
        }

        val clickResult = command.provider.execute(
            rootNode = rootNode,
            action = action
        )
        val clickResultMessage = if (
            clickResult.success &&
            command.provider == MapProvider.AMAP &&
            action == MapUiAction.CLICK_CHECK_IN
        ) {
            "${clickResult.message}，原子双击间隔=" +
                    "${AmapCheckInFollowUpPolicy.SECOND_CLICK_DELAY_MILLIS}ms"
        } else {
            clickResult.message
        }
        val actionEvidence = appendBoundedWorkerEvidence(
            structuredEvidence = profileEvidence,
            key = "result",
            value = clickResultMessage
        )
        reportStructuredStatus(
            event = "动作执行",
            structuredEvidence = actionEvidence
        )
        if (!clickResult.success) {
            finishCommand(
                command = command,
                result = createOperationResult(
                    command = command,
                    status = MapOperationStatus.FAILED,
                    startedAtMillis = startedAtMillis,
                    failureCode =
                        "${command.provider.name}_ACTION_FAILED",
                    failureMessage = clickResult.message,
                    evidenceText = appendProbeEvidence(
                        profileEvidence = actionEvidence,
                        packageName = packageName
                    )
                )
            )
            return
        }

        val nextActionCount =
            executionPolicy.actionCountAfterSuccessfulAction(
                actionCount = actionCount,
                action = action
            )
        val nextAmapCheckInStage = if (
            command.provider == MapProvider.AMAP &&
            action == MapUiAction.CLICK_CHECK_IN
        ) {
            AmapCheckInFollowUpPolicy
                .stageAfterAtomicDoubleClick()
        } else {
            amapCheckInStage
        }
        activeAmapCheckInStage = nextAmapCheckInStage
        val nextBaiduCheckInSubmitted =
            baiduCheckInSubmitted || (
                    command.provider == MapProvider.BAIDU &&
                            action == MapUiAction.CLICK_CHECK_IN
                    )
        mainHandler.postDelayed(
            {
                executeNextAction(
                    command = command,
                    startedAtMillis = startedAtMillis,
                    actionCount = nextActionCount,
                    lastFingerprint = fingerprint,
                    repeatedUnknownCount =
                        nextRepeatedUnknownCount,
                    windowWaitAttempt = 0,
                    amapCheckInStage = nextAmapCheckInStage,
                    baiduCheckInSubmitted =
                        nextBaiduCheckInSubmitted
                )
            },
            MAP_OPERATION_STEP_DELAY_MILLIS
        )
    }

    // 在地图位于后台时保留完整页面循环状态并等待窗口恢复。
    private fun waitForTargetWindowBeforeAction(
        command: MapCommand,
        startedAtMillis: Long,
        actionCount: Int,
        lastFingerprint: String,
        repeatedUnknownCount: Int,
        windowWaitAttempt: Int,
        amapCheckInStage: AmapCheckInStage,
        baiduCheckInSubmitted: Boolean
    ) {
        reportBackgroundWaitingIfNeeded(
            command = command,
            windowWaitAttempt = windowWaitAttempt
        )
        mainHandler.postDelayed(
            {
                executeNextAction(
                    command = command,
                    startedAtMillis = startedAtMillis,
                    actionCount = actionCount,
                    lastFingerprint = lastFingerprint,
                    repeatedUnknownCount = repeatedUnknownCount,
                    windowWaitAttempt = windowWaitAttempt + 1,
                    amapCheckInStage = amapCheckInStage,
                    baiduCheckInSubmitted =
                        baiduCheckInSubmitted
                )
            },
            windowRecoveryPolicy.nextWaitDelayMillis(windowWaitAttempt)
        )
    }

    // 清理百度成功页并按当前命令是否已提交打卡决定后续去向。
    private fun executeSuccessPageCleanup(
        command: MapCommand,
        startedAtMillis: Long,
        rootNode: AccessibilityNodeInfo,
        decision: MapUiDecision,
        profileEvidence: String,
        fingerprint: String,
        actionCount: Int,
        amapCheckInStage: AmapCheckInStage,
        baiduCheckInSubmitted: Boolean
    ) {
        val action = requireNotNull(decision.action)
        val cleanupResult = command.provider.execute(
            rootNode = rootNode,
            action = action
        )
        val cleanupEvidence =
            MapUiSuccessCleanupPolicy.appendEvidence(
                profileEvidence = profileEvidence,
                clickResult = cleanupResult
            )
        reportStructuredStatus(
            event = "成功页清理",
            structuredEvidence = cleanupEvidence
        )
        when (
            MapUiSuccessCleanupPolicy.dispositionAfter(
                checkInSubmitted = baiduCheckInSubmitted ||
                        (
                                command.provider == MapProvider.AMAP &&
                                        amapCheckInStage !=
                                        AmapCheckInStage.NONE
                                ),
                clickResult = cleanupResult
            )
        ) {
            MapUiSuccessCleanupDisposition.FINISH_SUCCESS ->
                finishProfileOutcome(
                    command = command,
                    startedAtMillis = startedAtMillis,
                    outcome = MapUiExecutionOutcome.SUCCESS,
                    decision = decision,
                    profileEvidence = cleanupEvidence
                )
            MapUiSuccessCleanupDisposition.CONTINUE_COMMAND -> {
                val nextActionCount =
                    executionPolicy.actionCountAfterSuccessfulAction(
                        actionCount = actionCount,
                        action = action
                    )
                mainHandler.postDelayed(
                    {
                        executeNextAction(
                            command = command,
                            startedAtMillis = startedAtMillis,
                            actionCount = nextActionCount,
                            lastFingerprint = fingerprint,
                            repeatedUnknownCount = 0,
                            windowWaitAttempt = 0,
                            amapCheckInStage = amapCheckInStage,
                            baiduCheckInSubmitted = false
                        )
                    },
                    MAP_OPERATION_STEP_DELAY_MILLIS
                )
            }
            MapUiSuccessCleanupDisposition.FAIL_COMMAND ->
                finishCommand(
                    command = command,
                    result = createOperationResult(
                        command = command,
                        status = MapOperationStatus.FAILED,
                        startedAtMillis = startedAtMillis,
                        failureCode =
                            "${command.provider.name}_STALE_SUCCESS_CLEANUP_FAILED",
                        failureMessage = cleanupResult.message,
                        evidenceText = appendProbeEvidence(
                            profileEvidence = cleanupEvidence,
                            packageName =
                                command.provider.packageName()
                        )
                    )
                )
        }
    }

    // 将 Profile 终态转换为持久化结果并结束命令。
    private fun finishProfileOutcome(
        command: MapCommand,
        startedAtMillis: Long,
        outcome: MapUiExecutionOutcome,
        decision: MapUiDecision,
        profileEvidence: String
    ) {
        val probeResult = resultProbe.probe(
            command.provider.packageName()
        )
        val effectiveOutcome = if (
            command.provider == MapProvider.AMAP &&
            activeAmapCheckInStage != AmapCheckInStage.NONE &&
            probeResult.status == MapOperationProbeStatus.SUCCESS &&
            outcome in listOf(
                MapUiExecutionOutcome.UNSUPPORTED,
                MapUiExecutionOutcome.TIMEOUT
            )
        ) {
            MapUiExecutionOutcome.SUCCESS
        } else {
            outcome
        }
        val effectiveDecision = if (effectiveOutcome != outcome) {
            decision.copy(
                evidence = "高德结果层经纬度与日期证据确认打卡成功",
                reason = ""
            )
        } else {
            decision
        }
        val failureCode = when (effectiveOutcome) {
            MapUiExecutionOutcome.SUCCESS,
            MapUiExecutionOutcome.ALREADY_COMPLETED -> ""
            MapUiExecutionOutcome.BLOCKED ->
                "${command.provider.name}_PROFILE_BLOCKED"
            MapUiExecutionOutcome.UNSUPPORTED ->
                "${command.provider.name}_PROFILE_UNSUPPORTED"
            MapUiExecutionOutcome.TIMEOUT ->
                "${command.provider.name}_ACTION_TIMEOUT"
            MapUiExecutionOutcome.CONTINUE -> ""
        }
        finishCommand(
            command = command,
            result = createOperationResult(
                command = command,
                status = effectiveOutcome.toOperationStatus(),
                startedAtMillis = startedAtMillis,
                failureCode = failureCode,
                failureMessage = effectiveDecision.reason
                    .ifBlank { effectiveDecision.evidence },
                evidenceText = appendProbeEvidenceValue(
                    profileEvidence = profileEvidence,
                    probeEvidence = probeResult.evidenceText
                )
            )
        )
    }

    // 返回地图服务商当前唯一受支持的页面 Profile。
    private fun MapProvider.uiProfile(): ProviderUiProfile {
        return when (this) {
            MapProvider.BAIDU -> BaiduUiProfile
            MapProvider.AMAP -> AmapUiProfile
        }
    }

    // 通过地图服务商 Adapter 采集当前页面快照。
    private fun MapProvider.captureSnapshot(
        rootNode: AccessibilityNodeInfo?,
        appVersion: String
    ): MapUiSnapshot {
        return when (this) {
            MapProvider.BAIDU ->
                BaiduMapAdapter.captureSnapshot(rootNode, appVersion)
            MapProvider.AMAP ->
                AmapMapAdapter.captureSnapshot(rootNode, appVersion)
        }
    }

    // 通过地图服务商 Adapter 执行 Profile 返回的单个动作。
    private fun MapProvider.execute(
        rootNode: AccessibilityNodeInfo?,
        action: MapUiAction
    ) = when (this) {
        MapProvider.BAIDU ->
            BaiduMapAdapter.execute(
                rootNode = rootNode,
                action = action,
                gestureTap = gestureTapDispatcher::tap,
                dismissSharePanel = {
                    service.performGlobalAction(
                        AccessibilityService.GLOBAL_ACTION_BACK
                    )
                }
            )
        MapProvider.AMAP ->
            AmapMapAdapter.execute(
                rootNode = rootNode,
                action = action,
                gestureTap = { x, y ->
                    gestureTapDispatcher.tapForPackage(
                        x = x,
                        y = y,
                        packageName = AMAP_PACKAGE_NAME
                    )
                },
                checkInGestureTap = { x, y ->
                    gestureTapDispatcher.tapTwiceForPackage(
                        x = x,
                        y = y,
                        packageName = AMAP_PACKAGE_NAME,
                        secondTapDelayMillis =
                            AmapCheckInFollowUpPolicy
                                .SECOND_CLICK_DELAY_MILLIS
                    )
                },
                dismissOverlay = {
                    service.performGlobalAction(
                        AccessibilityService.GLOBAL_ACTION_BACK
                    )
                }
            )
    }

    // 读取当前安装地图应用的版本名并在读取失败时返回空字符串。
    private fun appVersion(packageName: String): String {
        return runCatching {
            service.packageManager
                .getPackageInfo(packageName, 0)
                .versionName
                .orEmpty()
        }.getOrDefault("")
    }

    // 生成可持久化的 Profile 页面与动作证据。
    private fun buildProfileEvidence(
        provider: MapProvider,
        snapshot: MapUiSnapshot,
        profile: ProviderUiProfile,
        decision: MapUiDecision
    ): String {
        return buildMapUiEvidence(
            provider = provider,
            packageName = snapshot.packageName,
            appVersion = snapshot.appVersion,
            profileVersion = profile.profileVersion,
            page = decision.page,
            action = decision.action,
            evidence = decision.evidence
        )
    }

    // 仅为百度 UNKNOWN 页面追加受限的标签动作能力诊断。
    private fun appendBaiduUnknownDiagnostics(
        command: MapCommand,
        rootNode: AccessibilityNodeInfo,
        decision: MapUiDecision,
        profileEvidence: String
    ): String {
        if (
            command.provider != MapProvider.BAIDU ||
            decision.page != MapUiPage.UNKNOWN
        ) {
            return profileEvidence
        }
        val diagnosticEvidence = formatMapUiLabelDiagnostics(
            MapUiSnapshotCollector.collectLabelDiagnostics(
                rootNode = rootNode,
                targets = BAIDU_UNKNOWN_DIAGNOSTIC_TARGETS
            )
        )
        reportStructuredStatus(
            event = "UNKNOWN诊断",
            structuredEvidence = appendBoundedWorkerEvidence(
                structuredEvidence = "",
                key = "labelDiagnostics",
                value = diagnosticEvidence
            )
        )
        return appendBoundedWorkerEvidence(
            structuredEvidence = profileEvidence,
            key = "labelDiagnostics",
            value = diagnosticEvidence
        )
    }

    // 追加旧 Probe 只读证据但不使用其状态覆盖 Profile 终态。
    private fun appendProbeEvidence(
        profileEvidence: String,
        packageName: String
    ): String {
        val probeEvidence = resultProbe.probe(packageName).evidenceText
        return appendProbeEvidenceValue(
            profileEvidence = profileEvidence,
            probeEvidence = probeEvidence
        )
    }

    // 把已读取的 Probe 证据追加到结构化结果，避免瞬时结果层被重复读取丢失。
    private fun appendProbeEvidenceValue(
        profileEvidence: String,
        probeEvidence: String
    ): String {
        return appendBoundedWorkerEvidence(
            structuredEvidence = profileEvidence,
            key = "probeEvidence",
            value = probeEvidence
        )
    }

    // 统一输出包含完整页面循环字段的结构化状态。
    private fun reportStructuredStatus(
        event: String,
        structuredEvidence: String
    ) {
        onStatusChanged(
            buildStructuredStatusText(
                event = event,
                structuredEvidence = structuredEvidence
            )
        )
    }

    // 按节流策略输出结构化后台等待状态且不改变当前命令。
    private fun reportBackgroundWaitingIfNeeded(
        command: MapCommand,
        windowWaitAttempt: Int
    ) {
        if (
            !windowRecoveryPolicy.shouldReportWaiting(
                windowWaitAttempt
            )
        ) {
            return
        }
        val profile = command.provider.uiProfile()
        reportStructuredStatus(
            event = "后台等待",
            structuredEvidence = buildMapUiEvidence(
                provider = command.provider,
                packageName = profile.packageName,
                appVersion = appVersion(profile.packageName),
                profileVersion = profile.profileVersion,
                page = MapUiPage.UNKNOWN,
                action = MapUiAction.WAIT,
                evidence =
                    "目标地图窗口不可用，等待恢复，" +
                            "attempt=$windowWaitAttempt"
            )
        )
    }

    // 创建包含命令和事务标识的持久化结果。
    private fun createOperationResult(
        command: MapCommand,
        status: MapOperationStatus,
        startedAtMillis: Long,
        failureCode: String,
        failureMessage: String,
        evidenceText: String
    ): MapOperationResult {
        return MapOperationResult(
            provider = command.provider,
            flowVersion = DUAL_MAP_FLOW_VERSION,
            status = status,
            startedAt = startedAtMillis,
            completedAt = System.currentTimeMillis(),
            attempts = command.attempt,
            matchedPlaceText = "",
            matchedNode = "",
            failureCode = failureCode,
            failureMessage = failureMessage,
            evidenceText =
                evidenceText.take(WORKER_EVIDENCE_MAX_LENGTH),
            commandId = command.commandId,
            transactionId = command.transactionId
        )
    }

    // 回写平台证据并释放 Worker 处理下一个命令。
    private fun finishCommand(
        command: MapCommand,
        result: MapOperationResult
    ) {
        val completed = coordinator.completeCommand(
            commandId = command.commandId,
            result = result
        )
        reportStructuredStatus(
            event = if (completed) {
                "结果已回写"
            } else {
                "结果幂等忽略"
            },
            structuredEvidence = result.evidenceText
        )
        activeCommand = null
        activeStartedAtMillis = 0L
        activeAmapCheckInStage = AmapCheckInStage.NONE
        processNextCommand()
    }

    // 返回地图服务商对应的包名。
    private fun MapProvider.packageName(): String {
        return when (this) {
            MapProvider.BAIDU -> BAIDU_MAP_PACKAGE_NAME
            MapProvider.AMAP -> AMAP_PACKAGE_NAME
        }
    }

    private companion object {
        const val BAIDU_MAP_PACKAGE_NAME = "com.baidu.BaiduMap"
        const val AMAP_PACKAGE_NAME = "com.autonavi.minimap"
        const val DUAL_MAP_FLOW_VERSION = "dual_map_v5_parallel"
        const val COMMAND_POLL_INTERVAL_MILLIS = 500L
        const val MAP_OPERATION_INITIAL_DELAY_MILLIS = 4_000L
        const val MAP_OPERATION_STEP_DELAY_MILLIS = 1_500L
        val BAIDU_UNKNOWN_DIAGNOSTIC_TARGETS = listOf(
            MapUiLabelDiagnosticTarget(
                key = "mine",
                label = "我的"
            ),
            MapUiLabelDiagnosticTarget(
                key = "home",
                label = "首页"
            )
        )
    }

    private val workerId =
        "dual_map_accessibility_worker_${provider.name.lowercase()}"
}
