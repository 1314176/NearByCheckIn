// @Time: 2026/09/05 15:54
// @Author: MrWu
// @File: AccessibilityServiceSettingPolicyTest.kt
// @Software: Android Studio

package com.wu.nearbycheckin.platform.cruise

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityServiceSettingPolicyTest {

    // 验证完整类名和系统缩写类名均能识别目标无障碍服务。
    @Test
    fun recognizesFullAndShortClassNames() {
        val packageName = "com.wu.nearbycheckin"
        val className =
            "com.wu.nearbycheckin.platform.accessibility." +
                    "DualMapAccessibilityService"

        assertTrue(
            AccessibilityServiceSettingPolicy.containsComponent(
                enabledServices = "$packageName/$className",
                packageName = packageName,
                className = className
            )
        )
        assertTrue(
            AccessibilityServiceSettingPolicy.containsComponent(
                enabledServices =
                    "other.app/.OtherService:" +
                            "$packageName/.platform.accessibility." +
                            "DualMapAccessibilityService",
                packageName = packageName,
                className = className
            )
        )
    }

    // 验证格式错误和其他应用服务不会被误判为当前服务。
    @Test
    fun rejectsMalformedAndUnrelatedServices() {
        assertFalse(
            AccessibilityServiceSettingPolicy.containsComponent(
                enabledServices =
                    "invalid:other.app/.DualMapAccessibilityService",
                packageName = "com.wu.nearbycheckin",
                className =
                    "com.wu.nearbycheckin.platform.accessibility." +
                            "DualMapAccessibilityService"
            )
        )
    }
}
