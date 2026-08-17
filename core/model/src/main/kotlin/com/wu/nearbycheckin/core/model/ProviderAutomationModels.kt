// @Time: 2026/08/16 01:59
// @Author: MrWu
// @File: ProviderAutomationModels.kt
// @Software: Android Studio


package com.wu.nearbycheckin.core.model


import java.util.Locale


/**
 * 标识设备相对最近稳定位置的移动状态。
 */
enum class MovementClass {
    UNKNOWN,
    STATIC,
    MOVING,
    FAST_MOVING
}

/**
 * 保存不包含地图候选语义的纯设备位置快照。
 */
data class LocationSnapshot(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val speedKmh: Float,
    val timestampMillis: Long,
    val movementClass: MovementClass = MovementClass.UNKNOWN
) {

    // 判断位置值是否可用于刷新地图候选。
    fun isUsable(): Boolean {
        return latitude in -90.0..90.0 &&
                longitude in -180.0..180.0 &&
                !(latitude == 0.0 && longitude == 0.0) &&
                accuracyMeters >= 0F &&
                accuracyMeters < MAX_USABLE_ACCURACY_METERS &&
                timestampMillis > 0L
    }

    private companion object {
        const val MAX_USABLE_ACCURACY_METERS = 500F
    }
}

/**
 * 保存由单个地图应用候选列表读取到的地点信息。
 */
data class ProviderPlaceCandidate(
    val provider: MapProvider,
    val name: String,
    val address: String,
    val distanceText: String,
    val completed: Boolean,
    val fingerprint: String,
    val observedAtMillis: Long,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val stableHint: String = ""
) {

    // 生成用于持久化永久去重的 Provider 维度地点键。
    fun toPlaceKey(): ProviderPlaceKey {
        return ProviderPlaceKey(
            provider = provider,
            normalizedName = normalizeProviderText(name),
            normalizedAddress = normalizeProviderText(address),
            geoBucket = coordinateBucket(latitude, longitude),
            stableHint = stableHint.trim().ifBlank { fingerprint.trim() }
        )
    }
}

/**
 * 保存地图服务商隔离的永久地点完成键。
 */
data class ProviderPlaceKey(
    val provider: MapProvider,
    val normalizedName: String,
    val normalizedAddress: String,
    val geoBucket: String,
    val stableHint: String
) {

    // 返回可稳定写入 JSON 与集合的字符串表示。
    fun serialized(): String {
        return listOf(
            provider.name,
            normalizedName,
            normalizedAddress,
            geoBucket,
            stableHint
        ).joinToString(SEPARATOR) { value ->
            value.replace(SEPARATOR, ESCAPED_SEPARATOR)
        }
    }

    companion object {
        private const val SEPARATOR = "\u001F"
        private const val ESCAPED_SEPARATOR = " "

        // 从持久化字符串恢复 Provider 地点键，非法输入返回空值。
        fun parse(value: String): ProviderPlaceKey? {
            val parts = value.split(SEPARATOR)
            if (parts.size != 5) {
                return null
            }
            val provider = runCatching {
                MapProvider.valueOf(parts[0])
            }.getOrNull() ?: return null
            return ProviderPlaceKey(
                provider = provider,
                normalizedName = parts[1],
                normalizedAddress = parts[2],
                geoBucket = parts[3],
                stableHint = parts[4]
            )
        }
    }
}

/**
 * 标识单个 Provider 地点任务的持久化状态。
 */
enum class ProviderTaskStatus {
    QUEUED,
    ACTIVE,
    VERIFYING,
    RETRY_WAIT,
    BLOCKED,
    COMPLETED,
    SKIPPED,
    FAILED,
    CANCELLED
}

/**
 * 保存一个地图服务商处理一个候选地点的独立执行单元。
 */
data class ProviderCheckInTask(
    val taskId: String,
    val sessionId: String,
    val provider: MapProvider,
    val candidate: ProviderPlaceCandidate,
    val candidateKey: ProviderPlaceKey,
    val status: ProviderTaskStatus,
    val attempts: Int,
    val commandId: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val availableAtMillis: Long = 0L,
    val leaseOwner: String = "",
    val leaseUntilMillis: Long = 0L,
    val lastError: String = "",
    val evidence: MapOperationEvidence? = null
)

/**
 * 标识一个地图插件当前可观察的自动化状态。
 */
enum class ProviderAutomationState {
    DISABLED,
    LEGACY,
    READY,
    LIST_READING,
    SELECTING,
    VERIFYING,
    IDLE,
    BLOCKED
}

/**
 * 保存最终双 Provider 调度以及当前百度单 Provider 阶段的运行状态。
 */
data class DualMapRuntimeState(
    val baiduState: ProviderAutomationState = ProviderAutomationState.IDLE,
    val amapState: ProviderAutomationState = ProviderAutomationState.LEGACY,
    val activeProvider: MapProvider? = null,
    val latestLocation: LocationSnapshot? = null,
    val baiduListStale: Boolean = true,
    val amapListStale: Boolean = true,
    val baiduListFingerprint: String = "",
    val amapListFingerprint: String = "",
    val lastPluginPage: String = "",
    val lastAction: String = "",
    val lastEvidence: String = "",
    val baiduPluginPage: String = "",
    val baiduPluginAction: String = "",
    val baiduPluginEvidence: String = "",
    val amapPluginPage: String = "",
    val amapPluginAction: String = "",
    val amapPluginEvidence: String = "",
    val baiduVerifiedSuccessEpochDay: Long = 0L,
    val baiduDailyVerifiedSuccessCount: Int = 0,
    val baiduTotalVerifiedSuccessCount: Int = 0,
    val baiduObservedCheckInCount: Int = 0,
    val baiduLastVerifiedSuccessAtMillis: Long = 0L,
    val baiduLastVerifiedSuccessEvidence: String = "",
    val baiduDailyQuotaReachedAtMillis: Long = 0L,
    val baiduDailyQuotaResetAtMillis: Long = 0L,
    val baiduDailyQuotaEvidence: String = ""
)

/**
 * 保存插件对单次地图操作采集的结构化结果证据。
 */
data class MapOperationEvidence(
    val provider: MapProvider,
    val taskId: String,
    val commandId: String,
    val profileVersion: String,
    val page: String,
    val matchedText: String,
    val viewId: String,
    val beforeFingerprint: String,
    val afterFingerprint: String,
    val resultStatus: MapOperationStatus,
    val message: String,
    val observedAtMillis: Long,
    val beforeCheckInCount: Int? = null,
    val afterCheckInCount: Int? = null
)

// 将候选文本归一化为跨进程重启稳定的键字段。
private fun normalizeProviderText(value: String): String {
    return value.trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), "")
        .replace(Regex("[，,。.;；:：·•\\-—_]+"), "")
}

// 将可用经纬度压缩到约百米级网格，缺少坐标时返回空字符串。
private fun coordinateBucket(
    latitude: Double?,
    longitude: Double?
): String {
    if (
        latitude == null ||
        longitude == null ||
        latitude !in -90.0..90.0 ||
        longitude !in -180.0..180.0
    ) {
        return ""
    }
    return String.format(Locale.ROOT, "%.3f,%.3f", latitude, longitude)
}
