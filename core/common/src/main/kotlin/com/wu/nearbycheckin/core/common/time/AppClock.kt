// @Time: 2026/07/26 02:40
// @Author: MrWu
// @File: AppClock.kt
// @Software: Android Studio


package com.wu.nearbycheckin.core.common.time


/**
 * 为领域和应用用例提供可替换的当前时间。
 */
fun interface AppClock {

    // 返回当前 Unix 时间戳，单位为毫秒。
    fun nowMillis(): Long
}

/**
 * 使用系统时间实现生产环境时钟。
 */
object SystemAppClock : AppClock {

    // 返回系统当前时间。
    override fun nowMillis(): Long {
        return System.currentTimeMillis()
    }
}
