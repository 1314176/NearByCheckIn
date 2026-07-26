// @Time: 2026/07/27 01:39
// @Author: MrWu
// @File: AmapTransientMessagePolicyTest.kt
// @Software: Android Studio


package com.wu.nearbycheckin.platform.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmapTransientMessagePolicyTest {

    // 验证高德通知事件中的打卡频率限制可作为瞬时结果证据。
    @Test
    fun acceptsAmapRateLimitMessage() {
        assertTrue(
            AmapTransientMessagePolicy.isRateLimitEvidence(
                packageName = "com.autonavi.minimap",
                message = "打卡太频繁，请稍后再试"
            )
        )
    }

    // 验证其他应用的相同提示不会污染高德命令结果。
    @Test
    fun rejectsRateLimitMessageFromOtherPackage() {
        assertFalse(
            AmapTransientMessagePolicy.isRateLimitEvidence(
                packageName = "com.baidu.BaiduMap",
                message = "打卡太频繁，请稍后再试"
            )
        )
    }

    // 验证普通高德通知不会被误判为打卡成功证据。
    @Test
    fun rejectsUnrelatedAmapMessage() {
        assertFalse(
            AmapTransientMessagePolicy.isRateLimitEvidence(
                packageName = "com.autonavi.minimap",
                message = "路线规划完成"
            )
        )
    }
}
