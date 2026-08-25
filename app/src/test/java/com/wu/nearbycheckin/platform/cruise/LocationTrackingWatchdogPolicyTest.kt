// @Time: 2026/08/17 22:49
// @Author: MrWu
// @File: LocationTrackingWatchdogPolicyTest.kt
// @Software: Android Studio


package com.wu.nearbycheckin.platform.cruise


import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test


/**
 * 验证静止无回调或系统移除监听后的定位保活判定。
 */
class LocationTrackingWatchdogPolicyTest {

    private val policy = LocationTrackingWatchdogPolicy(
        staleAfterMillis = 25_000L
    )

    // 验证25秒内的真实定位不会触发重复注册。
    @Test
    fun keepsFreshLocationRegistration() {
        assertFalse(
            policy.shouldRestart(
                latestLocationTimeMillis = 80_000L,
                nowMillis = 100_000L
            )
        )
    }

    // 验证达到25秒无新定位时重新注册监听。
    @Test
    fun restartsStaleLocationRegistration() {
        assertTrue(
            policy.shouldRestart(
                latestLocationTimeMillis = 75_000L,
                nowMillis = 100_000L
            )
        )
    }

    // 验证首次启动尚无位置时主动恢复监听。
    @Test
    fun restartsWhenLocationIsMissing() {
        assertTrue(
            policy.shouldRestart(
                latestLocationTimeMillis = null,
                nowMillis = 100_000L
            )
        )
    }

    // 验证系统时钟短暂回拨时不会造成定位监听反复重注册。
    @Test
    fun keepsRegistrationWhenLocationTimeIsSlightlyAhead() {
        assertFalse(
            policy.shouldRestart(
                latestLocationTimeMillis = 101_000L,
                nowMillis = 100_000L
            )
        )
    }
}
