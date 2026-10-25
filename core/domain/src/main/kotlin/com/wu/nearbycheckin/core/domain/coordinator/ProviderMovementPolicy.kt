// @Time: 2026/08/16 02:18
// @Author: MrWu
// @File: ProviderMovementPolicy.kt
// @Software: Android Studio


package com.wu.nearbycheckin.core.domain.coordinator

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

import com.wu.nearbycheckin.core.model.MovementClass
import com.wu.nearbycheckin.core.model.LocationSnapshot


/**
 * 使用真实速度和相邻定位位移判定 Provider 调度移动状态。
 */
class ProviderMovementPolicy(
    private val movingSpeedKmh: Float = 1.5F,
    private val fastMovingSpeedKmh: Float = 10F,
    private val movingDistanceMeters: Float = 20F
) {

    // 将位置变化归类为静止、移动或高速移动。
    fun classify(
        speedKmh: Float,
        distanceMeters: Float?,
        elapsedMillis: Long?
    ): MovementClass {
        val displacementSpeedKmh = if (
            distanceMeters != null &&
            elapsedMillis != null &&
            elapsedMillis in 1..MAX_DISTANCE_WINDOW_MILLIS &&
            distanceMeters >= movingDistanceMeters
        ) {
            distanceMeters.coerceAtLeast(0F) *
                    MILLIS_TO_HOURS / elapsedMillis
        } else {
            0F
        }
        val effectiveSpeedKmh = maxOf(speedKmh, displacementSpeedKmh)
        if (effectiveSpeedKmh > fastMovingSpeedKmh) {
            return MovementClass.FAST_MOVING
        }
        if (effectiveSpeedKmh >= movingSpeedKmh) {
            return MovementClass.MOVING
        }
        if (
            distanceMeters != null &&
            elapsedMillis != null &&
            elapsedMillis in 1..MAX_DISTANCE_WINDOW_MILLIS &&
            distanceMeters >= movingDistanceMeters
        ) {
            return MovementClass.MOVING
        }
        return MovementClass.STATIC
    }

    private companion object {
        const val MAX_DISTANCE_WINDOW_MILLIS = 120_000L
        const val MILLIS_TO_HOURS = 3_600F
    }
}

/**
 * 提供双 Provider 移动、静止、耗尽和高速等待稳定的刷新间隔。
 */
class ProviderRefreshSchedulePolicy(
    private val staticActiveMillis: Long = 5_000L,
    private val movingActiveMillis: Long = 5_000L,
    private val fastMovingActiveMillis: Long = 2_000L,
    private val staticHeartbeatMillis: Long = 5_000L,
    private val movingHeartbeatMillis: Long = 5_000L,
    private val fastMovingHeartbeatMillis: Long = 2_000L
) {

    // 返回当前状态下一次列表刷新或稳定性复查间隔。
    fun nextIntervalMillis(
        movementClass: MovementClass,
        currentRangeExhausted: Boolean
    ): Long {
        return when (movementClass) {
            MovementClass.FAST_MOVING -> if (currentRangeExhausted) {
                fastMovingHeartbeatMillis
            } else {
                fastMovingActiveMillis
            }
            MovementClass.MOVING -> if (currentRangeExhausted) {
                movingHeartbeatMillis
            } else {
                movingActiveMillis
            }
            MovementClass.STATIC,
            MovementClass.UNKNOWN -> if (currentRangeExhausted) {
                staticHeartbeatMillis
            } else {
                staticActiveMillis
            }
        }
    }

    // 所有有效速度状态均允许打卡，实际频率由刷新间隔和事务验证共同限制。
    fun allowsCheckIn(movementClass: MovementClass): Boolean {
        return movementClass in MovementClass.entries
    }
}

/**
 * 以最近一次已提交候选为基准限制单个 Provider 打卡最小间隔。
 */
class ProviderSelectionRateLimiter(
    private val schedulePolicy: ProviderRefreshSchedulePolicy =
        ProviderRefreshSchedulePolicy()
) {
    private var nextAllowedAtMillis = 0L

    // 判断当前时间是否已经到达下一次允许提交候选的时刻。
    fun canSelect(nowMillis: Long): Boolean {
        return nowMillis >= nextAllowedAtMillis
    }

    // 在候选命令成功提交后记录对应速度状态的下一次允许时刻。
    fun recordSelection(
        nowMillis: Long,
        movementClass: MovementClass
    ) {
        nextAllowedAtMillis = nowMillis +
                schedulePolicy.nextIntervalMillis(
                    movementClass = movementClass,
                    currentRangeExhausted = false
                )
    }
}

/**
 * 统一判断 Provider 自动化能否使用一次定位快照。
 */
class ProviderLocationFreshnessPolicy(
    private val maxAgeMillis: Long = 30_000L,
    private val maxFutureMillis: Long = 10_000L
) {

    // 仅接受字段有效且时间处于可信窗口内的位置。
    fun isFresh(location: LocationSnapshot?, nowMillis: Long): Boolean {
        return location != null &&
                location.isUsable() &&
                location.timestampMillis >= nowMillis - maxAgeMillis &&
                location.timestampMillis <= nowMillis + maxFutureMillis
    }
}

/** 判断旧候选列表是否已经无法代表当前真实位置。 */
class ProviderCandidateListFreshnessPolicy {

    // 列表超过三十秒或真实位移扣除定位误差后超过五百米时，先返回入口重新读取。
    fun shouldRefresh(origin: LocationSnapshot?, current: LocationSnapshot?,
                      openedAtMillis: Long, nowMillis: Long): Boolean {
        if (nowMillis - openedAtMillis >= 30_000L) return true
        if (origin == null || current == null) return false
        val lat1 = Math.toRadians(origin.latitude)
        val lat2 = Math.toRadians(current.latitude)
        val deltaLon = Math.toRadians(current.longitude - origin.longitude)
        val h = sin((lat2 - lat1) / 2).let { it * it } +
                cos(lat1) * cos(lat2) * sin(deltaLon / 2).let { it * it }
        val distance = 2 * 6_371_000.0 * asin(sqrt(h.coerceIn(0.0, 1.0)))
        return distance - origin.accuracyMeters - current.accuracyMeters > 500.0
    }
}
