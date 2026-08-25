// @Time: 2026/08/17 22:49
// @Author: MrWu
// @File: LocationTrackingWatchdogPolicy.kt
// @Software: Android Studio


package com.wu.nearbycheckin.platform.cruise


/**
 * 判断真实定位监听是否已长时间没有产生新位置。
 */
internal class LocationTrackingWatchdogPolicy(
    private val staleAfterMillis: Long = DEFAULT_STALE_AFTER_MILLIS
) {

    // 缺少位置或位置年龄超过阈值时请求重新注册系统定位监听。
    fun shouldRestart(
        latestLocationTimeMillis: Long?,
        nowMillis: Long
    ): Boolean {
        val latestTime = latestLocationTimeMillis ?: return true
        if (latestTime <= 0L) {
            return true
        }
        if (nowMillis < latestTime) {
            return false
        }
        return nowMillis - latestTime >= staleAfterMillis
    }

    private companion object {
        const val DEFAULT_STALE_AFTER_MILLIS = 25_000L
    }
}
