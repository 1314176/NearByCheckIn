// @Time: 2026/08/16 02:18
// @Author: MrWu
// @File: ProviderMovementPolicyTest.kt
// @Software: Android Studio


package com.wu.nearbycheckin.core.domain.coordinator


import com.wu.nearbycheckin.core.model.MovementClass
import com.wu.nearbycheckin.core.model.LocationSnapshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test


/**
 * 验证百度移动分类和移动/静止持续调度规则。
 */
class ProviderMovementPolicyTest {

    // 高速驶离五百米外后刷新旧列表，静止误差漂移不触发位置刷新。
    @Test
    fun candidateListExpiresAfterRealMovementOrTime() {
        val policy = ProviderCandidateListFreshnessPolicy()
        val origin = LocationSnapshot(31.0, 121.0, 3.8F, 150F, 1_000)
        assertFalse(policy.shouldRefresh(origin, origin.copy(latitude = 31.0001), 1_000, 2_000))
        assertTrue(policy.shouldRefresh(origin, origin.copy(latitude = 31.006), 1_000, 17_000))
        assertFalse(policy.shouldRefresh(origin, origin, 1_000, 30_999))
        assertTrue(policy.shouldRefresh(origin, origin, 1_000, 31_000))
        assertFalse(policy.shouldRefresh(origin.copy(accuracyMeters = 400F),
            origin.copy(latitude = 31.006, accuracyMeters = 400F), 1_000, 17_000))
    }

    // 验证低速且小位移归类为静止。
    @Test
    fun classifiesStableLocationAsStatic() {
        assertEquals(
            MovementClass.STATIC,
            ProviderMovementPolicy().classify(0.2F, 3F, 5_000L)
        )
    }

    // 验证显著位移即使缺少速度也归类为移动。
    @Test
    fun classifiesRealDisplacementAsMoving() {
        assertEquals(
            MovementClass.MOVING,
            ProviderMovementPolicy().classify(0F, 20F, 10_000L)
        )
    }

    // 验证缺少 GPS 速度字段时仍能通过相邻位置推算高速状态。
    @Test
    fun derivesFastMovementFromLocationDisplacement() {
        assertEquals(
            MovementClass.FAST_MOVING,
            ProviderMovementPolicy().classify(0F, 42F, 1_000L)
        )
    }

    // 验证十公里边界使用五秒且超过边界使用两秒并允许地点点击。
    @Test
    fun speedBoundaryUsesFiveAndTwoSecondSchedules() {
        val movement = ProviderMovementPolicy()
        val schedule = ProviderRefreshSchedulePolicy()

        assertEquals(
            MovementClass.MOVING,
            movement.classify(10F, null, null)
        )
        assertEquals(
            5_000L,
            schedule.nextIntervalMillis(
                movement.classify(10F, null, null),
                currentRangeExhausted = false
            )
        )
        assertEquals(
            MovementClass.FAST_MOVING,
            movement.classify(10.01F, null, null)
        )
        assertEquals(
            2_000L,
            schedule.nextIntervalMillis(
                movement.classify(150F, null, null),
                currentRangeExhausted = false
            )
        )
        assertTrue(schedule.allowsCheckIn(MovementClass.FAST_MOVING))
    }

    // 验证静止状态无论当前范围是否耗尽都保持五秒刷新。
    @Test
    fun staticScheduleUsesFiveSecondActiveAndHeartbeat() {
        val schedule = ProviderRefreshSchedulePolicy()

        assertEquals(
            5_000L,
            schedule.nextIntervalMillis(
                MovementClass.STATIC,
                currentRangeExhausted = false
            )
        )
        assertEquals(
            5_000L,
            schedule.nextIntervalMillis(
                MovementClass.STATIC,
                currentRangeExhausted = true
            )
        )
    }

    // 验证候选提交后严格执行低速五秒和高速两秒最小间隔。
    @Test
    fun selectionRateLimiterEnforcesSpeedIntervals() {
        val limiter = ProviderSelectionRateLimiter()

        assertTrue(limiter.canSelect(1_000L))
        limiter.recordSelection(1_000L, MovementClass.MOVING)
        assertFalse(limiter.canSelect(5_999L))
        assertTrue(limiter.canSelect(6_000L))

        limiter.recordSelection(10_000L, MovementClass.FAST_MOVING)
        assertFalse(limiter.canSelect(11_999L))
        assertTrue(limiter.canSelect(12_000L))
    }

    // 验证过期、未来或低精度位置不会进入百度候选选择链路。
    @Test
    fun locationFreshnessRejectsUntrustedSnapshots() {
        val policy = ProviderLocationFreshnessPolicy()
        val current = LocationSnapshot(
            latitude = 31.2,
            longitude = 121.4,
            accuracyMeters = 20F,
            speedKmh = 120F,
            timestampMillis = 100_000L,
            movementClass = MovementClass.FAST_MOVING
        )

        assertTrue(policy.isFresh(current, 100_000L))
        assertFalse(
            policy.isFresh(current.copy(timestampMillis = 69_999L), 100_000L)
        )
        assertFalse(
            policy.isFresh(current.copy(timestampMillis = 110_001L), 100_000L)
        )
        assertTrue(
            policy.isFresh(current.copy(accuracyMeters = 499.9F), 100_000L)
        )
        assertFalse(
            policy.isFresh(current.copy(accuracyMeters = 500F), 100_000L)
        )
    }
}
