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

    // 从排队状态生成第一个尚未成功的地图命令。
    private fun handleExecutionStarted(
        state: DualMapState,
        event: DualMapEvent.ExecutionStarted
    ): DualMapTransition {
        if (state.status != DualMapTransactionStatus.QUEUED) {
            return DualMapTransition(state)
        }

        return startProvider(
            state = state,
            provider = nextIncompleteProvider(state) ?: return completed(state),
            nowMillis = event.nowMillis
        )
    }

    // 幂等消费与当前运行端匹配的地图结果。
    private fun handleMapResult(
        state: DualMapState,
        event: DualMapEvent.MapResultReceived
    ): DualMapTransition {
        val expectedProvider = when (state.status) {
            DualMapTransactionStatus.BAIDU_RUNNING -> MapProvider.BAIDU
            DualMapTransactionStatus.AMAP_RUNNING -> MapProvider.AMAP
            else -> null
        }

        if (expectedProvider != event.result.provider) {
            return DualMapTransition(state)
        }

        val updatedState = when (event.result.provider) {
            MapProvider.BAIDU -> state.copy(
                baiduResult = event.result
            )
            MapProvider.AMAP -> state.copy(
                amapResult = event.result
            )
        }

        if (event.result.status.isSuccessful()) {
            val nextProvider = nextIncompleteProvider(updatedState)
            return if (nextProvider == null) {
                completed(updatedState)
            } else {
                startProvider(
                    state = updatedState,
                    provider = nextProvider,
                    nowMillis = event.nowMillis
                )
            }
        }

        if (event.result.status == MapOperationStatus.BLOCKED) {
            val reason = event.result.failureMessage.ifBlank {
                "地图端需要人工处理"
            }
            return DualMapTransition(
                newState = updatedState.copy(
                    status = DualMapTransactionStatus.BLOCKED,
                    retryProvider = event.result.provider,
                    blockedReason = reason
                ),
                effects = listOf(
                    DomainEffect.ReportBlocked(reason)
                )
            )
        }

        val attempts = updatedState.attemptsFor(event.result.provider)
        if (attempts >= policy.maxAttemptsPerProvider) {
            return DualMapTransition(
                updatedState.copy(
                    status = DualMapTransactionStatus.FAILED,
                    retryProvider = null,
                    retryAtMillis = 0L,
                    blockedReason = event.result.failureMessage
                )
            )
        }

        val partialSuccess =
            updatedState.resultFor(event.result.provider.other())
                ?.status
                ?.isSuccessful() == true
        return DualMapTransition(
            updatedState.copy(
                status = if (partialSuccess) {
                    DualMapTransactionStatus.PARTIAL_SUCCESS
                } else {
                    DualMapTransactionStatus.RETRY_WAIT
                },
                retryProvider = event.result.provider,
                retryAtMillis = event.nowMillis + policy.retryDelayMillis
            )
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
            ?: nextIncompleteProvider(state)
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
        val newState = when (provider) {
            MapProvider.BAIDU -> state.copy(
                status = DualMapTransactionStatus.BAIDU_RUNNING,
                baiduAttempts = attempt,
                retryProvider = null,
                retryAtMillis = 0L
            )
            MapProvider.AMAP -> state.copy(
                status = DualMapTransactionStatus.AMAP_RUNNING,
                amapAttempts = attempt,
                retryProvider = null,
                retryAtMillis = 0L
            )
        }

        return DualMapTransition(
            newState = newState,
            commands = listOf(
                DualMapCommand.ExecuteMapOperation(
                    provider = provider,
                    attempt = attempt,
                    availableAtMillis = nowMillis
                )
            )
        )
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

    // 返回固定顺序中尚未成功的地图端。
    private fun nextIncompleteProvider(
        state: DualMapState
    ): MapProvider? {
        return PROVIDER_ORDER.firstOrNull { provider ->
            state.resultFor(provider)?.status?.isSuccessful() != true
        }
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

    // 返回另一个地图服务商。
    private fun MapProvider.other(): MapProvider {
        return when (this) {
            MapProvider.BAIDU -> MapProvider.AMAP
            MapProvider.AMAP -> MapProvider.BAIDU
        }
    }

    // 判断地图结果是否已经满足该端完成条件。
    private fun MapOperationStatus.isSuccessful(): Boolean {
        return this == MapOperationStatus.SUCCESS ||
                this == MapOperationStatus.ALREADY_COMPLETED
    }

    private companion object {
        val PROVIDER_ORDER = listOf(
            MapProvider.BAIDU,
            MapProvider.AMAP
        )
        val RETRYABLE_STATUSES = setOf(
            DualMapTransactionStatus.RETRY_WAIT,
            DualMapTransactionStatus.PARTIAL_SUCCESS
        )
    }
}
