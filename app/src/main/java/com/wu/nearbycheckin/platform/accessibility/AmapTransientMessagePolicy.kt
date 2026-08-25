// @Time: 2026/07/27 01:39
// @Author: MrWu
// @File: AmapTransientMessagePolicy.kt
// @Software: Android Studio


package com.wu.nearbycheckin.platform.accessibility

object AmapTransientMessagePolicy {

    // 判断高德通知类无障碍事件是否包含第二次打卡的频率限制证据。
    fun isRateLimitEvidence(
        packageName: String,
        message: String
    ): Boolean {
        return packageName == AMAP_PACKAGE_NAME &&
                RATE_LIMIT_MARKERS.any(message::contains)
    }

    private const val AMAP_PACKAGE_NAME = "com.autonavi.minimap"
    private val RATE_LIMIT_MARKERS = listOf(
        "打卡太频繁",
        "操作太频繁",
        "操作频繁"
    )
}
