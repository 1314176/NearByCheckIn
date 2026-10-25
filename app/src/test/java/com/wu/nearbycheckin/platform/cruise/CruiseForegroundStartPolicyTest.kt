// @Time: 2026/09/05 14:48
// @Author: MrWu
// @File: CruiseForegroundStartPolicyTest.kt
// @Software: Android Studio

package com.wu.nearbycheckin.platform.cruise

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CruiseForegroundStartPolicyTest {

    // 验证正常启动只执行一次，不误触发失败处理。
    @Test
    fun startsForegroundNormally() {
        var starts = 0
        assertTrue(CruiseForegroundStartPolicy.tryStart(
            start = { starts++ },
            onRejected = { throw AssertionError(it) }
        ))
        assertEquals(1, starts)
    }

    // 验证现场权限异常和后台启动限制均转为失败回调，避免服务进程崩溃。
    @Test
    fun handlesPermissionAndBackgroundStartRejection() {
        for (error in listOf(SecurityException("location"), IllegalStateException("background"))) {
            var failures = 0
            assertFalse(CruiseForegroundStartPolicy.tryStart(
                start = { throw error },
                onRejected = { assertEquals(error, it); failures++ }
            ))
            assertEquals(1, failures)
        }
    }

    // 验证未识别的代码错误不会被伪装成系统权限限制。
    @Test(expected = IllegalArgumentException::class)
    fun propagatesUnrelatedProgrammingErrors() {
        CruiseForegroundStartPolicy.tryStart(
            start = { throw IllegalArgumentException("unexpected") },
            onRejected = { throw AssertionError(it) }
        )
    }
}
