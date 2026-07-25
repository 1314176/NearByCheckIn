// @Time: 2026/07/20 18:00
// @Author: MrWu
// @File: DualMapCheckInModels.kt
// @Software: Android Studio


@file:Suppress("SpellCheckingInspection")


package com.wu.nearbycheckin.core.model


/**
 * 标识一次地图端操作所属的地图服务商。
 */
enum class MapProvider {
    BAIDU,
    AMAP
}

/**
 * 标识地图端打卡操作的可持久化结果状态。
 */
enum class MapOperationStatus {
    SUCCESS,
    ALREADY_COMPLETED,
    FAILED,
    BLOCKED,
    UNSUPPORTED,
    TIMEOUT,
    UNKNOWN
}

/**
 * 保存统一地点信息，作为百度和高德两端打卡事务的共同目标。
 *
 * @property unifiedId 统一地点标识。
 * @property normalizedName 归一化后的地点名称。
 * @property normalizedAddress 归一化后的地点地址。
 * @property latitude 地点纬度。
 * @property longitude 地点经度。
 * @property baiduPoiId 百度地点标识。
 * @property amapPoiId 高德地点标识。
 * @property firstSeenAt 首次发现时间戳。
 * @property lastSeenAt 最近发现时间戳。
 * @property completionStatus 地点整体完成状态。
 * @property lastAttemptAt 最近尝试时间戳。
 */
data class UnifiedPlace(
    val unifiedId: String,
    val normalizedName: String,
    val normalizedAddress: String,
    val latitude: Double,
    val longitude: Double,
    val baiduPoiId: String,
    val amapPoiId: String,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val completionStatus: String,
    val lastAttemptAt: Long
)

/**
 * 保存单个地图端一次操作的结果。
 *
 * @property provider 地图服务商。
 * @property flowVersion UI流程版本。
 * @property status 操作结果状态。
 * @property startedAt 操作开始时间戳。
 * @property completedAt 操作完成时间戳。
 * @property attempts 尝试次数。
 * @property matchedPlaceText 匹配到的地点文本。
 * @property matchedNode 命中的节点摘要。
 * @property failureCode 失败代码。
 * @property failureMessage 失败说明。
 * @property evidenceText 结果识别证据文本。
 */
data class MapOperationResult(
    val provider: MapProvider,
    val flowVersion: String,
    val status: MapOperationStatus,
    val startedAt: Long,
    val completedAt: Long,
    val attempts: Int,
    val matchedPlaceText: String,
    val matchedNode: String,
    val failureCode: String,
    val failureMessage: String,
    val evidenceText: String,
    val commandId: String = "",
    val transactionId: String = ""
)

/**
 * 保存同一地点在百度和高德两端的完整打卡事务记录。
 *
 * @property recordId 事务记录唯一标识。
 * @property sessionId 自动化会话标识。
 * @property unifiedPlaceId 统一地点标识。
 * @property placeName 地点名称。
 * @property placeAddress 地点地址。
 * @property locationAccuracy 定位精度，单位为米。
 * @property speedKmh 当前速度，单位为公里每小时。
 * @property distanceMeters 打卡时与地点距离，单位为米。
 * @property baiduResult 百度端结果。
 * @property amapResult 高德端结果。
 * @property overallStatus 整体事务状态。
 * @property createdAt 记录创建时间戳。
 * @property completedAt 记录完成时间戳。
 */
data class DualMapCheckInRecord(
    val recordId: String,
    val sessionId: String,
    val unifiedPlaceId: String,
    val placeName: String,
    val placeAddress: String,
    val locationAccuracy: Float,
    val speedKmh: Float,
    val distanceMeters: Int,
    val baiduResult: MapOperationResult,
    val amapResult: MapOperationResult,
    val overallStatus: String,
    val createdAt: Long,
    val completedAt: Long
)
