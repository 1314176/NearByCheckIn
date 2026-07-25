// @Time: 2026/07/20 18:00
// @Author: MrWu
// @File: CandidateEvaluator.kt
// @Software: Android Studio


package com.wu.nearbycheckin.core.domain.evaluator


import com.wu.nearbycheckin.core.model.AutomationSnapshot
import com.wu.nearbycheckin.core.model.DualMapTransactionStatus
import com.wu.nearbycheckin.core.model.NearbyPlaceCandidate


/**
 * 保存候选评估的可测试策略参数。
 */
data class CandidateEvaluationPolicy(
    val maxDistanceMeters: Int = 1_000,
    val requiredStayMillis: Long = 5_000L,
    val fastRequiredStayMillis: Long = 1_000L,
    val fastSpeedThresholdKmh: Float = 60F,
    val maxAccuracyMeters: Float = 100F,
    val maxLocationAgeMillis: Long = 120_000L,
    val failedRetryCooldownMillis: Long = 30 * 60 * 1_000L
)

/**
 * 保存一次候选评估所需的显式上下文。
 */
data class CandidateEvaluationContext(
    val snapshot: AutomationSnapshot,
    val nowMillis: Long
)

/**
 * 标识候选被拒绝的稳定原因。
 */
enum class CandidateRejectReason {
    INVALID_PLACE_ID,
    TOO_FAR,
    DUPLICATE_COMPLETED,
    ALREADY_QUEUED,
    RECENTLY_FAILED
}

/**
 * 标识候选暂缓入队的稳定原因。
 */
enum class CandidateDeferReason {
    INSUFFICIENT_STAY,
    UNSTABLE_LOCATION,
    STALE_LOCATION
}

/**
 * 保存候选排序使用的纯数值评分。
 */
data class CandidateScore(
    val value: Long
)

/**
 * 表示候选评估的接受、拒绝或暂缓结果。
 */
sealed interface CandidateDecision {

    data class Accept(
        val score: CandidateScore
    ) : CandidateDecision

    data class Reject(
        val reason: CandidateRejectReason
    ) : CandidateDecision

    data class Defer(
        val reason: CandidateDeferReason,
        val remainingMillis: Long = 0L
    ) : CandidateDecision
}

/**
 * 以纯 Kotlin 规则评估距离、停留、定位稳定性和永久去重。
 */
class CandidateEvaluator(
    private val policy: CandidateEvaluationPolicy = CandidateEvaluationPolicy()
) {

    // 按稳定顺序评估候选并返回可观测决策。
    fun evaluate(
        candidate: NearbyPlaceCandidate,
        context: CandidateEvaluationContext
    ): CandidateDecision {
        if (candidate.unifiedPlaceId.isBlank()) {
            return CandidateDecision.Reject(
                CandidateRejectReason.INVALID_PLACE_ID
            )
        }

        if (candidate.distanceMeters > policy.maxDistanceMeters) {
            return CandidateDecision.Reject(
                CandidateRejectReason.TOO_FAR
            )
        }

        val candidateKeys = candidate.providerPlaceIds +
                candidate.unifiedPlaceId
        if (
            candidateKeys.any { key ->
                key in context.snapshot.completedPlaceKeys
            }
        ) {
            return CandidateDecision.Reject(
                CandidateRejectReason.DUPLICATE_COMPLETED
            )
        }

        if (
            context.snapshot.transactions.any { transaction ->
                transaction.candidate.unifiedPlaceId ==
                        candidate.unifiedPlaceId &&
                        transaction.state.status !in TERMINAL_STATUSES
            }
        ) {
            return CandidateDecision.Reject(
                CandidateRejectReason.ALREADY_QUEUED
            )
        }

        if (
            context.snapshot.transactions.any { transaction ->
                transaction.candidate.unifiedPlaceId ==
                        candidate.unifiedPlaceId &&
                        transaction.state.status ==
                        DualMapTransactionStatus.FAILED &&
                        context.nowMillis >= transaction.updatedAtMillis &&
                        context.nowMillis - transaction.updatedAtMillis <
                        policy.failedRetryCooldownMillis
            }
        ) {
            return CandidateDecision.Reject(
                CandidateRejectReason.RECENTLY_FAILED
            )
        }

        if (candidate.locationAccuracyMeters > policy.maxAccuracyMeters) {
            return CandidateDecision.Defer(
                CandidateDeferReason.UNSTABLE_LOCATION
            )
        }

        val locationAgeMillis =
            context.nowMillis - candidate.locationTimeMillis
        if (
            candidate.locationTimeMillis <= 0L ||
            locationAgeMillis > policy.maxLocationAgeMillis
        ) {
            return CandidateDecision.Defer(
                CandidateDeferReason.STALE_LOCATION
            )
        }

        val requiredStayMillis =
            if (candidate.speedKmh > policy.fastSpeedThresholdKmh) {
                policy.fastRequiredStayMillis
            } else {
                policy.requiredStayMillis
            }
        val stayedMillis =
            context.nowMillis - candidate.firstSeenAtMillis

        if (stayedMillis < requiredStayMillis) {
            return CandidateDecision.Defer(
                reason = CandidateDeferReason.INSUFFICIENT_STAY,
                remainingMillis = requiredStayMillis - stayedMillis
            )
        }

        return CandidateDecision.Accept(
            CandidateScore(
                value = SCORE_BASE -
                        candidate.distanceMeters.toLong() * DISTANCE_WEIGHT -
                        candidate.firstSeenAtMillis / TIME_WEIGHT_DIVISOR
            )
        )
    }

    private companion object {
        const val SCORE_BASE = 1_000_000_000_000L
        const val DISTANCE_WEIGHT = 1_000L
        const val TIME_WEIGHT_DIVISOR = 1_000L

        val TERMINAL_STATUSES = setOf(
            DualMapTransactionStatus.COMPLETED,
            DualMapTransactionStatus.FAILED,
            DualMapTransactionStatus.CANCELLED
        )
    }
}
