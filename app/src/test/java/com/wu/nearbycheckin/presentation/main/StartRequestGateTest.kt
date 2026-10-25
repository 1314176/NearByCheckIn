// @Time: 2026/09/05 15:54
// @Author: MrWu
// @File: StartRequestGateTest.kt
// @Software: Android Studio

package com.wu.nearbycheckin.presentation.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartRequestGateTest {

    // 验证启动进行中拒绝重复请求且释放后允许重新启动。
    @Test
    fun mergesConcurrentRequestsAndAllowsRetryAfterRelease() {
        val gate = StartRequestGate()

        assertTrue(gate.tryAcquire())
        assertFalse(gate.tryAcquire())

        gate.release()

        assertTrue(gate.tryAcquire())
    }
}
