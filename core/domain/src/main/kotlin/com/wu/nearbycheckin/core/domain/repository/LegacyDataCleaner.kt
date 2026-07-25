// @Time: 2026/07/26 02:59
// @Author: MrWu
// @File: LegacyDataCleaner.kt
// @Software: Android Studio


package com.wu.nearbycheckin.core.domain.repository


/**
 * 隔离清理用例与旧版 Android 数据实现。
 */
fun interface LegacyDataCleaner {

    // 清理旧版本兼容数据。
    fun clearLegacyData(): Boolean
}
