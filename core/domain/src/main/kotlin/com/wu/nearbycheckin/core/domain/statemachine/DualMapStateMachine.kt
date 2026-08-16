// @Time: 2026/07/20 18:00
// @Author: MrWu
// @File: DualMapStateMachine.kt
// @Software: Android Studio


package com.wu.nearbycheckin.core.domain.statemachine


import com.wu.nearbycheckin.core.model.DualMapState
import com.wu.nearbycheckin.core.model.DualMapTransactionStatus
import com.wu.nearbycheckin.core.model.MapOperationResult
import com.wu.nearbycheckin.core.model.MapOperationStatus
import com.wu.nearbycheckin.core.model.MapProvider


/**
 * 保存状态机的显式重试策略。
 */
data class DualMapStateMachinePolicy(
    val maxAttemptsPerProvider: Int = 3,
    val retryDelayMillis: Long = 5_000L
)

/**
 * 表示状态机可消费的全部领域事件。
 */
sealed interface DualMapEvent {

    data class ExecutionStarted(
        val nowMillis: Long
    ) : DualMapEvent

    data class MapResultReceived(
        val result: MapOperationResult,
        val nowMillis: Long
    ) : DualMapEvent

    data class ProviderResultReused(
        val result: MapOperationResult,
        val nowMillis: Long
    ) : DualMapEvent

    data class RetryDue(
        val nowMillis: Long
    ) : DualMapEvent

    data class ManualResume(
        val nowMillis: Long
    ) : DualMapEvent

    data class Cancel(
        val reason: String
    ) : DualMapEvent
}

/**
 * 表示状态机要求平台执行的单端地图操作。
 */
sealed interface DualMapCommand {

    data class ExecuteMapOperation(
        val provider: MapProvider,
        val attempt: Int,
        val availableAtMillis: Long
    ) : DualMapCommand
}

/**
 * 表示状态机产生的无平台依赖领域副作用。
 */
sealed interface DomainEffect {

    data object MarkPlaceCompleted : DomainEffect

    data class ReportBlocked(
        val reason: String
    ) : DomainEffect
}

/**
 * 保存一次纯 reducer 的新状态、命令和领域副作用。
 */
data class DualMapTransition(
    val newState: DualMapState,
    val commands: List<DualMapCommand> = emptyList(),
    val effects: List<DomainEffect> = emptyList()
)

/**
 * 以 State + Event 纯函数方式推进双地图事务。
 */
class DualMapStateMachine(
    private val policy: DualMapStateMachinePolicy =
        DualMapStateMachinePolicy()
) {

    // 根据当前状态和显式事件计算下一状态与命令。
    fun reduce(
        state: DualMapState,
        event: DualMapEvent
    ): DualMapTransition {
        return when (event) {
            is DualMapEvent.ExecutionStarted ->
                handleExecutionStarted(state, event)
            is DualMapEvent.MapResultReceived ->
                handleMapResult(state, event)
            is DualMapEvent.ProviderResultReused ->
                handleReusedResult(state, event)
            is DualMapEvent.RetryDue ->
                handleRetryDue(state, event.nowMillis)
            is DualMapEvent.ManualResume ->
                handleManualResume(state, event.nowMillis)
            is DualMapEvent.Cancel ->
                DualMapTransition(
                    state.copy(
                        status = DualMapTransactionStatus.CANCELLED,
                        blockedReason = event.reason
                    )
                )
        }
    }

    // 从排队状态同时生成百度和高德两条独立命令。
    private fun handleExecutionStarted(
        state: DualMapState,
        event: DualMapEvent.ExecutionStarted
    ): DualMapTransition {
        if (state.status != DualMapTransactionStatus.QUEUED) {
            return DualMapTransition(state)
        }

        val nextState = state.copy(
            status = DualMapTransactionStatus.RUNNING,
            baiduAttempts = if (state.baiduTerminal) {
                state.baiduAttempts
            } else {
                state.baiduAttempts + 1
            },
            amapAttempts = if (state.amapTerminal) {
                state.amapAttempts
            } else {
                state.amapAttempts + 1
            },
            retryProvider = null,
            retryAtMillis = 0L,
            blockedReason = ""
        )
        val commands = MapProvider.entries.mapNotNull { provider ->
            if (nextState.isTerminalFor(provider)) {
                null
            } else {
                executeCommand(
                    state = nextState,
                    provider = provider,
                    availableAtMillis = event.nowMillis
                )
            }
        }
        return if (commands.isEmpty()) {
            settle(nextState)
        } else {
            DualMapTransition(
                newState = nextState,
                commands = commands
            )
        }
    }

    // 消费任意尚未终结地图端的结果并独立安排该端重试。
    private fun handleMapResult(
        state: DualMapState,
        event: DualMapEvent.MapResultReceived
    ): DualMapTransition {
        val provider = event.result.provider
        if (
            state.status in TERMINAL_STATUSES ||
            state.isTerminalFor(provider)
        ) {
            return DualMapTransition(state)
        }

        val updatedState = state.withResult(event.result)
        if (event.result.status.isSuccessful()) {
            return settle(
                updatedState.withTerminal(provider, true)
            )
        }

        if (event.result.status == MapOperationStatus.BLOCKED) {
            val reason = event.result.failureMessage.ifBlank {
                "地图端需要人工处理"
            }
            return DualMapTransition(
                newState = updatedState.copy(
                    status = DualMapTransactionStatus.BLOCKED,
                    retryProvider = provider,
                    retryAtMillis = 0L,
                    blockedReason = reason
                ),
                effects = listOf(DomainEffect.ReportBlocked(reason))
            )
        }

        val attempts = updatedState.attemptsFor(provider)
        if (attempts >= policy.maxAttemptsPerProvider) {
            return settle(
                updatedState.withTerminal(provider, true)
            )
        }

        val nextState = updatedState
            .withAttempts(provider, attempts + 1)
            .copy(
                status = runningStatus(updatedState),
                retryProvider = null,
                retryAtMillis = 0L,
                blockedReason = ""
            )
        return DualMapTransition(
            newState = nextState,
            commands = listOf(
                executeCommand(
                    state = nextState,
                    provider = provider,
                    availableAtMillis =
                        event.nowMillis + policy.retryDelayMillis
                )
            )
        )
    }

    // 把同一真实位置领导事务的高德终态复用给重复事务。
    private fun handleReusedResult(
        state: DualMapState,
        event: DualMapEvent.ProviderResultReused
    ): DualMapTransition {
        if (
            state.status in TERMINAL_STATUSES ||
            state.isTerminalFor(event.result.provider)
        ) {
            return DualMapTransition(state)
        }
        return settle(
            state.withResult(event.result)
                .withTerminal(event.result.provider, true)
        )
    }

    // 在退避到期后只重新生成失败端命令。
    private fun handleRetryDue(
        state: DualMapState,
        nowMillis: Long
    ): DualMapTransition {
        if (
            state.status !in RETRYABLE_STATUSES ||
            state.retryAtMillis > nowMillis
        ) {
            return DualMapTransition(state)
        }

        val provider = state.retryProvider
            ?: return DualMapTransition(state)
        return startProvider(
            state = state,
            provider = provider,
            nowMillis = nowMillis
        )
    }

    // 从人工阻断状态恢复失败端执行。
    private fun handleManualResume(
        state: DualMapState,
        nowMillis: Long
    ): DualMapTransition {
        if (state.status != DualMapTransactionStatus.BLOCKED) {
            return DualMapTransition(state)
        }

        val provider = state.retryProvider
            ?: MapProvider.entries.firstOrNull { candidate ->
                !state.isTerminalFor(candidate)
            }
            ?: return completed(state)
        return startProvider(
            state = state.copy(blockedReason = ""),
            provider = provider,
            nowMillis = nowMillis
        )
    }

    // 生成指定地图端的下一次串行命令。
    private fun startProvider(
        state: DualMapState,
        provider: MapProvider,
        nowMillis: Long
    ): DualMapTransition {
        val attempt = state.attemptsFor(provider) + 1
        val newState = state
            .withAttempts(provider, attempt)
            .withTerminal(provider, false)
            .withoutResult(provider)
            .copy(
                status = runningStatus(state.withoutResult(provider)),
                retryProvider = null,
                retryAtMillis = 0L,
                blockedReason = ""
            )

        return DualMapTransition(
            newState = newState,
            commands = listOf(
                executeCommand(
                    state = newState,
                    provider = provider,
                    availableAtMillis = nowMillis
                )
            )
        )
    }

    // 根据当前尝试次数构造单端持久化执行命令。
    private fun executeCommand(
        state: DualMapState,
        provider: MapProvider,
        availableAtMillis: Long
    ): DualMapCommand.ExecuteMapOperation {
        return DualMapCommand.ExecuteMapOperation(
            provider = provider,
            attempt = state.attemptsFor(provider),
            availableAtMillis = availableAtMillis
        )
    }

    // 根据双端是否均已终结收敛为完成、失败或继续运行状态。
    private fun settle(state: DualMapState): DualMapTransition {
        if (state.baiduTerminal && state.amapTerminal) {
            return if (
                state.baiduResult?.status?.isSuccessful() == true &&
                state.amapResult?.status?.isSuccessful() == true
            ) {
                completed(state)
            } else {
                DualMapTransition(
                    state.copy(
                        status = DualMapTransactionStatus.FAILED,
                        retryProvider = null,
                        retryAtMillis = 0L,
                        blockedReason = failedReason(state)
                    )
                )
            }
        }
        val blockedProvider = MapProvider.entries.firstOrNull { provider ->
            !state.isTerminalFor(provider) &&
                    state.resultFor(provider)?.status ==
                    MapOperationStatus.BLOCKED
        }
        if (blockedProvider != null) {
            return DualMapTransition(
                state.copy(
                    status = DualMapTransactionStatus.BLOCKED,
                    retryProvider = blockedProvider,
                    blockedReason = state.resultFor(blockedProvider)
                        ?.failureMessage
                        .orEmpty()
                )
            )
        }
        return DualMapTransition(
            state.copy(
                status = runningStatus(state),
                retryProvider = null,
                retryAtMillis = 0L,
                blockedReason = ""
            )
        )
    }

    // 返回当前并行事务的人类可读运行状态。
    private fun runningStatus(state: DualMapState):
            DualMapTransactionStatus {
        val hasSuccess = MapProvider.entries.any { provider ->
            state.resultFor(provider)?.status?.isSuccessful() == true
        }
        return if (hasSuccess) {
            DualMapTransactionStatus.PARTIAL_SUCCESS
        } else {
            DualMapTransactionStatus.RUNNING
        }
    }

    // 返回双端最终失败时优先展示的具体原因。
    private fun failedReason(state: DualMapState): String {
        return MapProvider.entries.firstNotNullOfOrNull { provider ->
            state.resultFor(provider)
                ?.takeIf { result -> !result.status.isSuccessful() }
                ?.failureMessage
                ?.takeIf(String::isNotBlank)
        }.orEmpty()
    }

    // 将双端成功状态收敛为幂等终态。
    private fun completed(state: DualMapState): DualMapTransition {
        return DualMapTransition(
            newState = state.copy(
                status = DualMapTransactionStatus.COMPLETED,
                retryProvider = null,
                retryAtMillis = 0L,
                blockedReason = ""
            ),
            effects = listOf(
                DomainEffect.MarkPlaceCompleted
            )
        )
    }

    // 返回指定地图端已经发出的尝试次数。
    private fun DualMapState.attemptsFor(provider: MapProvider): Int {
        return when (provider) {
            MapProvider.BAIDU -> baiduAttempts
            MapProvider.AMAP -> amapAttempts
        }
    }

    // 返回指定地图端最近一次持久化结果。
    private fun DualMapState.resultFor(
        provider: MapProvider
    ): MapOperationResult? {
        return when (provider) {
            MapProvider.BAIDU -> baiduResult
            MapProvider.AMAP -> amapResult
        }
    }

    // 返回指定地图端是否已经形成不可再重试的终态。
    private fun DualMapState.isTerminalFor(provider: MapProvider): Boolean {
        return when (provider) {
            MapProvider.BAIDU -> baiduTerminal
            MapProvider.AMAP -> amapTerminal
        }
    }

    // 替换指定地图端最近一次结果。
    private fun DualMapState.withResult(
        result: MapOperationResult
    ): DualMapState {
        return when (result.provider) {
            MapProvider.BAIDU -> copy(baiduResult = result)
            MapProvider.AMAP -> copy(amapResult = result)
        }
    }

    // 清除指定地图端旧结果以便人工恢复后重新识别。
    private fun DualMapState.withoutResult(
        provider: MapProvider
    ): DualMapState {
        return when (provider) {
            MapProvider.BAIDU -> copy(baiduResult = null)
            MapProvider.AMAP -> copy(amapResult = null)
        }
    }

    // 更新指定地图端已经生成的命令尝试次数。
    private fun DualMapState.withAttempts(
        provider: MapProvider,
        attempts: Int
    ): DualMapState {
        return when (provider) {
            MapProvider.BAIDU -> copy(baiduAttempts = attempts)
            MapProvider.AMAP -> copy(amapAttempts = attempts)
        }
    }

    // 更新指定地图端是否已形成终态。
    private fun DualMapState.withTerminal(
        provider: MapProvider,
        terminal: Boolean
    ): DualMapState {
        return when (provider) {
            MapProvider.BAIDU -> copy(baiduTerminal = terminal)
            MapProvider.AMAP -> copy(amapTerminal = terminal)
        }
    }

    // 判断地图结果是否已经满足该端完成条件。
    private fun MapOperationStatus.isSuccessful(): Boolean {
        return this == MapOperationStatus.SUCCESS ||
                this == MapOperationStatus.ALREADY_COMPLETED
    }

    private companion object {
        val RETRYABLE_STATUSES = setOf(
            DualMapTransactionStatus.RETRY_WAIT,
            DualMapTransactionStatus.PARTIAL_SUCCESS
        )
        val TERMINAL_STATUSES = setOf(
            DualMapTransactionStatus.COMPLETED,
            DualMapTransactionStatus.FAILED,
            DualMapTransactionStatus.CANCELLED
        )
    }
}
