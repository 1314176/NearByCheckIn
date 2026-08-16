// @Time: 2026/08/16 02:09
// @Author: MrWu
// @File: ProviderStateMachineTest.kt
// @Software: Android Studio


package com.wu.nearbycheckin.core.domain.statemachine


import org.junit.Assert.assertEquals
import org.junit.Test

import com.wu.nearbycheckin.core.model.ProviderAutomationState


class ProviderStateMachineTest {

    @Test
    fun `百度候选读取选择验证和耗尽形成闭环`() {
        val machine = ProviderStateMachine()
        val reading = machine.reduce(
            ProviderAutomationState.READY,
            ProviderAutomationEvent.RefreshRequested
        ).state
        val selecting = machine.reduce(
            reading,
            ProviderAutomationEvent.CandidatesLoaded(true)
        ).state
        val verifying = machine.reduce(
            selecting,
            ProviderAutomationEvent.CandidateSelected
        ).state
        val idle = machine.reduce(
            verifying,
            ProviderAutomationEvent.VerificationFinished(false)
        ).state

        assertEquals(ProviderAutomationState.LIST_READING, reading)
        assertEquals(ProviderAutomationState.SELECTING, selecting)
        assertEquals(ProviderAutomationState.VERIFYING, verifying)
        assertEquals(ProviderAutomationState.IDLE, idle)
    }

    @Test
    fun `阻断后只有手动恢复返回 ready`() {
        val machine = ProviderStateMachine()
        val blocked = machine.reduce(
            ProviderAutomationState.VERIFYING,
            ProviderAutomationEvent.Blocked("登录")
        )
        val ignoredRefresh = machine.reduce(
            blocked.state,
            ProviderAutomationEvent.RefreshRequested
        ).state
        val resumed = machine.reduce(
            ignoredRefresh,
            ProviderAutomationEvent.ManualResume
        ).state

        assertEquals(ProviderAutomationState.BLOCKED, blocked.state)
        assertEquals("登录", blocked.blockedReason)
        assertEquals(ProviderAutomationState.BLOCKED, ignoredRefresh)
        assertEquals(ProviderAutomationState.READY, resumed)
    }
}
