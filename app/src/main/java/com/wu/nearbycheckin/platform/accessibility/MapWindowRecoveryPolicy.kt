// @Time: 2026/07/26 00:10
// @Author: MrWu
// @File: MapWindowRecoveryPolicy.kt
// @Software: Codex

package com.wu.nearbycheckin.platform.accessibility

/**
 * 控制后台地图窗口恢复请求的频率与等待退避。
 */
internal class MapWindowRecoveryPolicy(
    private val statusIntervalAttempts: Int = 5,
    private val fastWaitMaxAttempts: Int = 30,
    private val fastWaitDelayMillis: Long = 1_000L,
    private val idleWaitDelayMillis: Long = 5_000L
) {

    init {
        require(statusIntervalAttempts > 0)
        require(fastWaitMaxAttempts >= 0)
    }

    // 判断本轮是否需要提示双地图位于后台且任务仍被保留。
    fun shouldReportWaiting(windowWaitAttempt: Int): Boolean {
        return windowWaitAttempt >= 0 &&
                windowWaitAttempt % statusIntervalAttempts == 0
    }

    // 返回短时后台与长时后台状态对应的等待间隔。
    fun nextWaitDelayMillis(windowWaitAttempt: Int): Long {
        return if (windowWaitAttempt < fastWaitMaxAttempts) {
            fastWaitDelayMillis
        } else {
            idleWaitDelayMillis
        }
    }
}
