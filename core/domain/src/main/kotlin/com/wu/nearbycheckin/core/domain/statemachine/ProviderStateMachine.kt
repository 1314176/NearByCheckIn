// @Time: 2026/08/16 02:09
// @Author: MrWu
// @File: ProviderStateMachine.kt
// @Software: Android Studio


package com.wu.nearbycheckin.core.domain.statemachine


import com.wu.nearbycheckin.core.model.ProviderAutomationState


/**
 * 表示单 Provider 自动化状态机可消费的领域事件。
 */
sealed interface ProviderAutomationEvent {
    data object Enable : ProviderAutomationEvent
    data object RefreshRequested : ProviderAutomationEvent
    data class CandidatesLoaded(
        val hasExecutableCandidate: Boolean
    ) : ProviderAutomationEvent
    data object CandidateSelected : ProviderAutomationEvent
    data object VerificationStarted : ProviderAutomationEvent
    data class VerificationFinished(
        val hasMoreCandidates: Boolean
    ) : ProviderAutomationEvent
    data object ListExhausted : ProviderAutomationEvent
    data class Blocked(val reason: String) : ProviderAutomationEvent
    data object ManualResume : ProviderAutomationEvent
    data object Disable : ProviderAutomationEvent
    data object UseLegacy : ProviderAutomationEvent
}

/**
 * 保存单 Provider 状态转换结果和阻断原因。
 */
data class ProviderAutomationTransition(
    val state: ProviderAutomationState,
    val blockedReason: String = ""
)

/**
 * 以纯 Kotlin reducer 推进单 Provider 页面与任务状态。
 */
class ProviderStateMachine {

    // 根据当前状态和事件返回确定性下一状态。
    fun reduce(
        current: ProviderAutomationState,
        event: ProviderAutomationEvent
    ): ProviderAutomationTransition {
        return when (event) {
            ProviderAutomationEvent.Disable ->
                transition(ProviderAutomationState.DISABLED)
            ProviderAutomationEvent.UseLegacy ->
                transition(ProviderAutomationState.LEGACY)
            ProviderAutomationEvent.Enable -> when (current) {
                ProviderAutomationState.DISABLED,
                ProviderAutomationState.LEGACY,
                ProviderAutomationState.IDLE ->
                    transition(ProviderAutomationState.READY)
                else -> transition(current)
            }
            ProviderAutomationEvent.RefreshRequested -> when (current) {
                ProviderAutomationState.SELECTING,
                ProviderAutomationState.VERIFYING,
                ProviderAutomationState.BLOCKED,
                ProviderAutomationState.DISABLED,
                ProviderAutomationState.LEGACY -> transition(current)
                else -> transition(ProviderAutomationState.LIST_READING)
            }
            is ProviderAutomationEvent.CandidatesLoaded -> {
                if (current != ProviderAutomationState.LIST_READING) {
                    transition(current)
                } else if (event.hasExecutableCandidate) {
                    transition(ProviderAutomationState.SELECTING)
                } else {
                    transition(ProviderAutomationState.IDLE)
                }
            }
            ProviderAutomationEvent.CandidateSelected -> when (current) {
                ProviderAutomationState.SELECTING,
                ProviderAutomationState.READY ->
                    transition(ProviderAutomationState.VERIFYING)
                else -> transition(current)
            }
            ProviderAutomationEvent.VerificationStarted -> when (current) {
                ProviderAutomationState.SELECTING,
                ProviderAutomationState.VERIFYING ->
                    transition(ProviderAutomationState.VERIFYING)
                else -> transition(current)
            }
            is ProviderAutomationEvent.VerificationFinished -> {
                if (current != ProviderAutomationState.VERIFYING) {
                    transition(current)
                } else if (event.hasMoreCandidates) {
                    transition(ProviderAutomationState.SELECTING)
                } else {
                    transition(ProviderAutomationState.IDLE)
                }
            }
            ProviderAutomationEvent.ListExhausted ->
                transition(ProviderAutomationState.IDLE)
            is ProviderAutomationEvent.Blocked ->
                ProviderAutomationTransition(
                    state = ProviderAutomationState.BLOCKED,
                    blockedReason = event.reason
                )
            ProviderAutomationEvent.ManualResume -> {
                if (current == ProviderAutomationState.BLOCKED) {
                    transition(ProviderAutomationState.READY)
                } else {
                    transition(current)
                }
            }
        }
    }

    // 创建无阻断原因的普通状态转换。
    private fun transition(
        state: ProviderAutomationState
    ): ProviderAutomationTransition {
        return ProviderAutomationTransition(state = state)
    }
}
