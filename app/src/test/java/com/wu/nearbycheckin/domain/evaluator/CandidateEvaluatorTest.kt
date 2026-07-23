// @Time: 2026/07/20 12:00
// @Author: MrWu
// @File: CandidateEvaluatorTest.kt
// @Software: Codex

package com.wu.nearbycheckin.domain.evaluator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

import com.wu.nearbycheckin.domain.model.AutomationSnapshot
import com.wu.nearbycheckin.domain.model.CheckInTransaction
import com.wu.nearbycheckin.domain.model.DualMapState
import com.wu.nearbycheckin.domain.model.DualMapTransactionStatus
import com.wu.nearbycheckin.domain.model.NearbyPlaceCandidate

/**
 * 验证候选距离、停留、定位稳定和永久去重规则。
 */
class CandidateEvaluatorTest {

    private val evaluator = CandidateEvaluator()

    // 验证满足全部规则的候选会被接受。
    @Test
    fun acceptsStableCandidateAfterRequiredStay() {
        val decision = evaluator.evaluate(
            candidate = candidate(
                firstSeenAtMillis = 1_000L,
                observedAtMillis = 6_000L,
                locationTimeMillis = 6_000L
            ),
            context = CandidateEvaluationContext(
                snapshot = AutomationSnapshot(),
                nowMillis = 6_000L
            )
        )

        assertTrue(decision is CandidateDecision.Accept)
    }

    // 验证停留不足时只暂缓而不创建事务。
    @Test
    fun defersCandidateBeforeRequiredStay() {
        val decision = evaluator.evaluate(
            candidate = candidate(
                firstSeenAtMillis = 4_000L,
                observedAtMillis = 6_000L,
                locationTimeMillis = 6_000L
            ),
            context = CandidateEvaluationContext(
                snapshot = AutomationSnapshot(),
                nowMillis = 6_000L
            )
        )

        assertEquals(
            CandidateDeferReason.INSUFFICIENT_STAY,
            (decision as CandidateDecision.Defer).reason
        )
        assertEquals(3_000L, decision.remainingMillis)
    }

    // 验证永久完成键会幂等拒绝重复候选。
    @Test
    fun rejectsPermanentlyCompletedCandidate() {
        val decision = evaluator.evaluate(
            candidate = candidate(
                firstSeenAtMillis = 1_000L,
                observedAtMillis = 6_000L,
                locationTimeMillis = 6_000L
            ),
            context = CandidateEvaluationContext(
                snapshot = AutomationSnapshot(
                    completedPlaceKeys = setOf("poi-1")
                ),
                nowMillis = 6_000L
            )
        )

        assertEquals(
            CandidateRejectReason.DUPLICATE_COMPLETED,
            (decision as CandidateDecision.Reject).reason
        )
    }

    // 验证超出最大距离的候选会被拒绝。
    @Test
    fun rejectsCandidateOutsideDistanceLimit() {
        val decision = evaluator.evaluate(
            candidate = candidate(
                distanceMeters = 1_001,
                firstSeenAtMillis = 1_000L,
                observedAtMillis = 6_000L,
                locationTimeMillis = 6_000L
            ),
            context = CandidateEvaluationContext(
                snapshot = AutomationSnapshot(),
                nowMillis = 6_000L
            )
        )

        assertEquals(
            CandidateRejectReason.TOO_FAR,
            (decision as CandidateDecision.Reject).reason
        )
    }

    // 验证最终失败的地点在冷却期内不会立即重新入队。
    @Test
    fun rejectsRecentlyFailedCandidateDuringCooldown() {
        val failedCandidate = candidate(
            firstSeenAtMillis = 1_000L,
            observedAtMillis = 5_500L,
            locationTimeMillis = 5_500L
        )
        val decision = evaluator.evaluate(
            candidate = failedCandidate.copy(
                observedAtMillis = 6_000L,
                locationTimeMillis = 6_000L
            ),
            context = CandidateEvaluationContext(
                snapshot = AutomationSnapshot(
                    transactions = listOf(
                        CheckInTransaction(
                            transactionId = "transaction-1",
                            sessionId = "session-1",
                            candidate = failedCandidate,
                            state = DualMapState(
                                status = DualMapTransactionStatus.FAILED
                            ),
                            createdAtMillis = 1_000L,
                            updatedAtMillis = 5_500L
                        )
                    )
                ),
                nowMillis = 6_000L
            )
        )

        assertEquals(
            CandidateRejectReason.RECENTLY_FAILED,
            (decision as CandidateDecision.Reject).reason
        )
    }

    // 创建字段完整且默认可靠的测试候选。
    private fun candidate(
        distanceMeters: Int = 100,
        firstSeenAtMillis: Long,
        observedAtMillis: Long,
        locationTimeMillis: Long
    ): NearbyPlaceCandidate {
        return NearbyPlaceCandidate(
            unifiedPlaceId = "poi-1",
            providerPlaceIds = setOf("poi-1"),
            name = "测试地点",
            address = "测试地址",
            category = "测试分类",
            latitude = 31.0,
            longitude = 121.0,
            distanceMeters = distanceMeters,
            locationAccuracyMeters = 10F,
            speedKmh = 0F,
            locationTimeMillis = locationTimeMillis,
            firstSeenAtMillis = firstSeenAtMillis,
            observedAtMillis = observedAtMillis
        )
    }
}
