// @Time: 2026/08/02 20:44
// @Author: MrWu
// @File: AmapRealLocationDeduplicationPolicy.kt
// @Software: Android Studio


package com.wu.nearbycheckin.core.domain.coordinator


import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

import com.wu.nearbycheckin.core.model.AutomationSnapshot
import com.wu.nearbycheckin.core.model.CheckInTransaction
import com.wu.nearbycheckin.core.model.NearbyPlaceCandidate


/**
 * 按设备真实坐标识别同一会话内只需执行一次的高德位置。
 */
object AmapRealLocationDeduplicationPolicy {

    // 判断指定事务是否为同一真实位置中最早创建的高德执行事务。
    fun isLocationLeader(
        snapshot: AutomationSnapshot,
        transactionId: String
    ): Boolean {
        val transaction = snapshot.transactions.firstOrNull { item ->
            item.transactionId == transactionId
        } ?: return false
        if (!transaction.candidate.hasRealLocationEvidence()) {
            return true
        }
        return snapshot.transactions
            .asSequence()
            .filter { item ->
                item.sessionId == transaction.sessionId &&
                        item.candidate.hasRealLocationEvidence() &&
                        isSameRealLocation(
                            transaction.candidate,
                            item.candidate
                        )
            }
            .minWithOrNull(
                compareBy<CheckInTransaction> { item ->
                    item.createdAtMillis
                }.thenBy { item -> item.transactionId }
            )
            ?.transactionId == transactionId
    }

    // 判断两个候选是否来自精度容差内的同一个设备真实位置。
    fun isSameRealLocation(
        first: NearbyPlaceCandidate,
        second: NearbyPlaceCandidate
    ): Boolean {
        if (
            !first.hasUsableDeviceLocation() ||
            !second.hasUsableDeviceLocation()
        ) {
            return first.locationTimeMillis > 0L &&
                    first.locationTimeMillis == second.locationTimeMillis
        }
        val accuracyThreshold = maxOf(
            MINIMUM_DEDUPE_DISTANCE_METERS,
            first.locationAccuracyMeters.toDouble(),
            second.locationAccuracyMeters.toDouble()
        ).coerceAtMost(MAXIMUM_DEDUPE_DISTANCE_METERS)
        return distanceMeters(first, second) <= accuracyThreshold
    }

    // 判断候选是否具有设备坐标或旧快照中的同批定位时间证据。
    private fun NearbyPlaceCandidate.hasRealLocationEvidence(): Boolean {
        return hasUsableDeviceLocation() || locationTimeMillis > 0L
    }

    // 判断候选是否保存了可用于真实位置去重的设备坐标。
    private fun NearbyPlaceCandidate.hasUsableDeviceLocation(): Boolean {
        return deviceLatitude in -90.0..90.0 &&
                deviceLongitude in -180.0..180.0 &&
                !(deviceLatitude == 0.0 && deviceLongitude == 0.0)
    }

    // 使用 Haversine 公式计算两个设备真实坐标之间的距离。
    private fun distanceMeters(
        first: NearbyPlaceCandidate,
        second: NearbyPlaceCandidate
    ): Double {
        val firstLatitude = Math.toRadians(first.deviceLatitude)
        val secondLatitude = Math.toRadians(second.deviceLatitude)
        val latitudeDelta = secondLatitude - firstLatitude
        val longitudeDelta = Math.toRadians(
            second.deviceLongitude - first.deviceLongitude
        )
        val haversine = sin(latitudeDelta / 2.0) *
                sin(latitudeDelta / 2.0) +
                cos(firstLatitude) * cos(secondLatitude) *
                sin(longitudeDelta / 2.0) *
                sin(longitudeDelta / 2.0)
        return EARTH_RADIUS_METERS * 2.0 *
                atan2(sqrt(haversine), sqrt(1.0 - haversine))
    }

    private const val EARTH_RADIUS_METERS = 6_371_000.0
    private const val MINIMUM_DEDUPE_DISTANCE_METERS = 50.0
    private const val MAXIMUM_DEDUPE_DISTANCE_METERS = 100.0
}
