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
 * 串行领取地图命令、调用 Adapter 并回写结果证据。
 */
class DualMapCommandWorker(
    private val service: AccessibilityService,
    private val coordinator: CheckInCoordinator,
    private val onStatusChanged: (String) -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val resultProbe = MapOperationResultProbe(service)
    private val gestureTapDispatcher =
        AccessibilityGestureTapDispatcher(service)
    private val executionPolicy = MapUiExecutionPolicy()
    private val windowRecoveryPolicy =
        MapWindowRecoveryPolicy()
    private var activeCommand: MapCommand? = null

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
    }

    // 领取下一个命令并在地图位于后台时保留任务等待窗口恢复。
    private fun processNextCommand() {
        if (activeCommand != null) {
            return
        }

        val command = coordinator.claimNextCommand(WORKER_ID)
            ?: return
        activeCommand = command
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
                    startedAtMillis = System.currentTimeMillis()
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
        windowWaitAttempt: Int = 0
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
                windowWaitAttempt = windowWaitAttempt
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
        if (
            !executionPolicy.isSupportedAppVersion(
                appVersion = currentAppVersion,
                supportedAppVersion = profile.supportedAppVersion
            )
        ) {
            val unsupportedDecision = MapUiDecision(
                page = MapUiPage.UNKNOWN,
                action = null,
                evidence = "应用版本不受支持",
                reason = "应用版本必须完整匹配"
            )
            val unsupportedEvidence = buildProfileEvidence(
                provider = command.provider,
                snapshot = snapshot,
                profile = profile,
                decision = unsupportedDecision
            )
            reportStructuredStatus(
                event = "页面识别",
                structuredEvidence = unsupportedEvidence
            )
            finishCommand(
                command = command,
                result = createOperationResult(
                    command = command,
                    status = MapOperationStatus.UNSUPPORTED,
                    startedAtMillis = startedAtMillis,
                    failureCode =
                        "${command.provider.name}_VERSION_UNSUPPORTED",
                    failureMessage =
                        "${command.provider.displayName()}地图版本不受支持",
                    evidenceText = appendProbeEvidence(
                        profileEvidence = unsupportedEvidence,
                        packageName = packageName
                    )
                )
            )
            return
        }

        val decision = profile.decide(snapshot)
        val fingerprint = snapshot.fingerprint()
        val nextRepeatedUnknownCount =
            executionPolicy.repeatedUnknownCountFor(
                page = decision.page,
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
        if (action == null || action == MapUiAction.WAIT) {
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
                        windowWaitAttempt = 0
                    )
                },
                MAP_OPERATION_STEP_DELAY_MILLIS
            )
            return
        }

        val clickResult = command.provider.execute(
            rootNode = rootNode,
            action = action,
            appVersion = currentAppVersion
        )
        val actionEvidence = appendBoundedWorkerEvidence(
            structuredEvidence = profileEvidence,
            key = "result",
            value = clickResult.message
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
        mainHandler.postDelayed(
            {
                executeNextAction(
                    command = command,
                    startedAtMillis = startedAtMillis,
                    actionCount = nextActionCount,
                    lastFingerprint = fingerprint,
                    repeatedUnknownCount =
                        nextRepeatedUnknownCount,
                    windowWaitAttempt = 0
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
        windowWaitAttempt: Int
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
                    windowWaitAttempt = windowWaitAttempt + 1
                )
            },
            windowRecoveryPolicy.nextWaitDelayMillis(windowWaitAttempt)
        )
    }

    // 将 Profile 终态转换为持久化结果并结束命令。
    private fun finishProfileOutcome(
        command: MapCommand,
        startedAtMillis: Long,
        outcome: MapUiExecutionOutcome,
        decision: MapUiDecision,
        profileEvidence: String
    ) {
        val failureCode = when (outcome) {
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
                status = outcome.toOperationStatus(),
                startedAtMillis = startedAtMillis,
                failureCode = failureCode,
                failureMessage = decision.reason
                    .ifBlank { decision.evidence },
                evidenceText = appendProbeEvidence(
                    profileEvidence = profileEvidence,
                    packageName = command.provider.packageName()
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
        action: MapUiAction,
        appVersion: String
    ) = when (this) {
        MapProvider.BAIDU ->
            BaiduMapAdapter.execute(
                rootNode = rootNode,
                action = action,
                appVersion = appVersion,
                gestureTap = gestureTapDispatcher::tap
            )
        MapProvider.AMAP ->
            AmapMapAdapter.execute(rootNode, action)
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
        processNextCommand()
    }

    // 返回地图服务商对应的包名。
    private fun MapProvider.packageName(): String {
        return when (this) {
            MapProvider.BAIDU -> BAIDU_MAP_PACKAGE_NAME
            MapProvider.AMAP -> AMAP_PACKAGE_NAME
        }
    }

    // 返回地图服务商对应的中文名称。
    private fun MapProvider.displayName(): String {
        return when (this) {
            MapProvider.BAIDU -> "百度"
            MapProvider.AMAP -> "高德"
        }
    }

    private companion object {
        const val WORKER_ID = "dual_map_accessibility_worker"
        const val BAIDU_MAP_PACKAGE_NAME = "com.baidu.BaiduMap"
        const val AMAP_PACKAGE_NAME = "com.autonavi.minimap"
        const val DUAL_MAP_FLOW_VERSION = "dual_map_v4"
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
}
