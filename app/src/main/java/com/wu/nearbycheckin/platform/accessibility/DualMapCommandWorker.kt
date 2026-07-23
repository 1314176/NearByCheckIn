// @Time: 2026/07/20 15:45
// @Author: MrWu
// @File: DualMapCommandWorker.kt
// @Software: Codex

package com.wu.nearbycheckin.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper

import com.wu.nearbycheckin.domain.coordinator.CheckInCoordinator
import com.wu.nearbycheckin.domain.model.MapCommand
import com.wu.nearbycheckin.domain.model.MapOperationResult
import com.wu.nearbycheckin.domain.model.MapOperationStatus
import com.wu.nearbycheckin.domain.model.MapProvider
import com.wu.nearbycheckin.domain.repository.CheckInTransactionRepository
import com.wu.nearbycheckin.platform.maps.AmapMapAdapter
import com.wu.nearbycheckin.platform.maps.BaiduMapAdapter

/**
 * 串行领取地图命令、调用 Adapter 并回写结果证据。
 */
class DualMapCommandWorker(
    service: AccessibilityService,
    private val coordinator: CheckInCoordinator,
    private val repository: CheckInTransactionRepository,
    private val onStatusChanged: (String) -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val resultProbe = MapOperationResultProbe(service)
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

    // 在双地图窗口就绪时领取一个命令。
    private fun processNextCommand() {
        if (activeCommand != null) {
            return
        }
        val diagnostics = repository.currentSnapshot().diagnostics
        if (!diagnostics.baiduReady || !diagnostics.amapReady) {
            return
        }

        val command = coordinator.claimNextCommand(WORKER_ID)
            ?: return
        activeCommand = command
        onStatusChanged(
            "正在执行${command.provider.displayName()}地图命令，" +
                    "attempt=${command.attempt}"
        )
        mainHandler.postDelayed(
            {
                executeStep(
                    command = command,
                    stepIndex = 0,
                    startedAtMillis = System.currentTimeMillis()
                )
            },
            MAP_OPERATION_INITIAL_DELAY_MILLIS
        )
    }

    // 按地图服务商执行一个 Adapter 步骤。
    private fun executeStep(
        command: MapCommand,
        stepIndex: Int,
        startedAtMillis: Long,
        windowWaitAttempt: Int = 0
    ) {
        if (activeCommand?.commandId != command.commandId) {
            return
        }

        val rootNode = resultProbe.findWindowRootNode(
            command.provider.packageName()
        )
        val adjacentRootNode = resultProbe.findWindowRootNode(
            command.provider.other().packageName()
        )
        if (rootNode == null || adjacentRootNode == null) {
            waitForWindowsBeforeStep(
                command = command,
                stepIndex = stepIndex,
                startedAtMillis = startedAtMillis,
                windowWaitAttempt = windowWaitAttempt
            )
            return
        }
        val clickResult = when (command.provider) {
            MapProvider.BAIDU -> when (stepIndex) {
                0 -> BaiduMapAdapter.clickMine(rootNode)
                1 -> BaiduMapAdapter.clickCheckInPoint(rootNode)
                2 -> BaiduMapAdapter.clickImmediateCheckIn(rootNode)
                else -> null
            }
            MapProvider.AMAP -> when (stepIndex) {
                0 -> AmapMapAdapter.clickMine(rootNode)
                1 -> AmapMapAdapter.clickFootprint(rootNode)
                2 -> AmapMapAdapter.clickCheckIn(rootNode)
                else -> null
            }
        }
        if (clickResult == null) {
            scheduleResultVerification(
                command = command,
                startedAtMillis = startedAtMillis,
                verificationAttempt = 1
            )
            return
        }
        if (!clickResult.success) {
            finishCommand(
                command = command,
                result = createOperationResult(
                    command = command,
                    status = MapOperationStatus.FAILED,
                    startedAtMillis = startedAtMillis,
                    failureCode =
                        "${command.provider.name}_STEP_FAILED",
                    failureMessage = clickResult.message,
                    evidenceText = clickResult.message
                )
            )
            return
        }

        onStatusChanged(
            "${command.provider.displayName()}地图步骤" +
                    "${stepIndex + 1}已执行：${clickResult.message}"
        )
        mainHandler.postDelayed(
            {
                executeStep(
                    command = command,
                    stepIndex = stepIndex + 1,
                    startedAtMillis = startedAtMillis,
                    windowWaitAttempt = 0
                )
            },
            MAP_OPERATION_STEP_DELAY_MILLIS
        )
    }

    // 在双地图窗口短暂丢失时保留当前步骤并等待恢复。
    private fun waitForWindowsBeforeStep(
        command: MapCommand,
        stepIndex: Int,
        startedAtMillis: Long,
        windowWaitAttempt: Int
    ) {
        if (windowWaitAttempt >= MAP_WINDOW_WAIT_MAX_ATTEMPTS) {
            finishCommandAsWindowsUnavailable(
                command = command,
                startedAtMillis = startedAtMillis
            )
            return
        }
        if (
            windowWaitAttempt == 0 ||
            windowWaitAttempt % MAP_WINDOW_STATUS_INTERVAL_ATTEMPTS == 0
        ) {
            onStatusChanged(
                "双地图窗口暂不可用，正在等待恢复分屏"
            )
        }
        mainHandler.postDelayed(
            {
                executeStep(
                    command = command,
                    stepIndex = stepIndex,
                    startedAtMillis = startedAtMillis,
                    windowWaitAttempt = windowWaitAttempt + 1
                )
            },
            MAP_WINDOW_WAIT_DELAY_MILLIS
        )
    }

    // 延迟识别当前地图命令的终态证据。
    private fun scheduleResultVerification(
        command: MapCommand,
        startedAtMillis: Long,
        verificationAttempt: Int,
        windowWaitAttempt: Int = 0
    ) {
        mainHandler.postDelayed(
            {
                if (activeCommand?.commandId != command.commandId) {
                    return@postDelayed
                }
                if (!areDualMapWindowsAvailable()) {
                    if (windowWaitAttempt >= MAP_WINDOW_WAIT_MAX_ATTEMPTS) {
                        finishCommandAsWindowsUnavailable(
                            command = command,
                            startedAtMillis = startedAtMillis
                        )
                    } else {
                        scheduleResultVerification(
                            command = command,
                            startedAtMillis = startedAtMillis,
                            verificationAttempt = verificationAttempt,
                            windowWaitAttempt = windowWaitAttempt + 1
                        )
                    }
                    return@postDelayed
                }
                val probeResult = resultProbe.probe(
                    command.provider.packageName()
                )
                if (probeResult.status.isTerminal) {
                    finishCommand(
                        command = command,
                        result = createOperationResult(
                            command = command,
                            status = probeResult.status.operationStatus,
                            startedAtMillis = startedAtMillis,
                            failureCode = probeResult.failureCode,
                            failureMessage =
                                probeResult.status.displayText,
                            evidenceText = probeResult.evidenceText
                        )
                    )
                    return@postDelayed
                }
                if (
                    verificationAttempt <
                    MAP_RESULT_VERIFY_MAX_ATTEMPTS
                ) {
                    scheduleResultVerification(
                        command = command,
                        startedAtMillis = startedAtMillis,
                        verificationAttempt =
                            verificationAttempt + 1,
                        windowWaitAttempt = 0
                    )
                    return@postDelayed
                }

                finishCommand(
                    command = command,
                    result = createOperationResult(
                        command = command,
                        status = MapOperationStatus.TIMEOUT,
                        startedAtMillis = startedAtMillis,
                        failureCode =
                            "${command.provider.name}_RESULT_TIMEOUT",
                        failureMessage =
                            "${command.provider.displayName()}地图结果探测超时",
                        evidenceText = probeResult.evidenceText
                    )
                )
            },
            MAP_RESULT_VERIFY_DELAY_MILLIS
        )
    }

    // 读取当前无障碍窗口并确认百度、高德同时存在。
    private fun areDualMapWindowsAvailable(): Boolean {
        return resultProbe.findWindowRootNode(
            BAIDU_MAP_PACKAGE_NAME
        ) != null && resultProbe.findWindowRootNode(
            AMAP_PACKAGE_NAME
        ) != null
    }

    // 窗口长时不可用时将事务转为可恢复阻断而非失败。
    private fun finishCommandAsWindowsUnavailable(
        command: MapCommand,
        startedAtMillis: Long
    ) {
        finishCommand(
            command = command,
            result = createOperationResult(
                command = command,
                status = MapOperationStatus.BLOCKED,
                startedAtMillis = startedAtMillis,
                failureCode = DUAL_MAP_WINDOWS_UNAVAILABLE_CODE,
                failureMessage = "双地图分屏窗口不可用，请恢复分屏后继续",
                evidenceText = "百度或高德窗口根节点不可用"
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
            evidenceText = evidenceText,
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
        onStatusChanged(
            if (completed) {
                "${command.provider.displayName()}地图结果已回写：" +
                        result.status
            } else {
                "${command.provider.displayName()}地图结果已被幂等忽略"
            }
        )
        activeCommand = null
        mainHandler.post(pollRunnable)
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

    // 返回当前地图服务商的另一端。
    private fun MapProvider.other(): MapProvider {
        return when (this) {
            MapProvider.BAIDU -> MapProvider.AMAP
            MapProvider.AMAP -> MapProvider.BAIDU
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
        const val MAP_RESULT_VERIFY_DELAY_MILLIS = 2_000L
        const val MAP_RESULT_VERIFY_MAX_ATTEMPTS = 5
        const val MAP_WINDOW_WAIT_DELAY_MILLIS = 1_000L
        const val MAP_WINDOW_WAIT_MAX_ATTEMPTS = 30
        const val MAP_WINDOW_STATUS_INTERVAL_ATTEMPTS = 5
        const val DUAL_MAP_WINDOWS_UNAVAILABLE_CODE =
            "DUAL_MAP_WINDOWS_UNAVAILABLE"
    }
}
