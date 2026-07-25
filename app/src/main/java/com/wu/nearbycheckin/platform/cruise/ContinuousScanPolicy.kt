// @Time: 2026/07/25 23:55
// @Author: MrWu
// @File: ContinuousScanPolicy.kt
// @Software: Codex

package com.wu.nearbycheckin.platform.cruise

/**
 * 根据候选发现结果和移动速度选择下一次 POI 扫描间隔。
 */
internal class ContinuousScanPolicy(
    private val activeIntervalMillis: Long = 15_000L,
    private val idleIntervalMillis: Long = 60_000L,
    private val fastIntervalMillis: Long = 1_000L,
    private val fastSpeedThresholdKmh: Float = 60F
) {

    private var currentRangeExhausted = false

    // 记录当前范围是否仍有已接受或等待确认的候选。
    fun recordCandidateOutcome(
        acceptedCount: Int,
        deferredCount: Int
    ) {
        currentRangeExhausted =
            acceptedCount <= 0 && deferredCount <= 0
    }

    // 在位置明显变化后恢复正常巡检频率。
    fun resetForLocationChange() {
        currentRangeExhausted = false
    }

    // 返回高速、活跃或范围耗尽状态对应的扫描间隔。
    fun nextIntervalMillis(speedKmh: Float): Long {
        return when {
            speedKmh > fastSpeedThresholdKmh ->
                fastIntervalMillis
            currentRangeExhausted ->
                idleIntervalMillis
            else ->
                activeIntervalMillis
        }
    }
}
