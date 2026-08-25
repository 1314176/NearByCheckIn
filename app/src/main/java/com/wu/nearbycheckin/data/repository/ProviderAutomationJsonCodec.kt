// @Time: 2026/08/16 02:02
// @Author: MrWu
// @File: ProviderAutomationJsonCodec.kt
// @Software: Android Studio


package com.wu.nearbycheckin.data.repository


import org.json.JSONArray
import org.json.JSONObject

import com.wu.nearbycheckin.core.model.DualMapRuntimeState
import com.wu.nearbycheckin.core.model.LocationSnapshot
import com.wu.nearbycheckin.core.model.MapOperationEvidence
import com.wu.nearbycheckin.core.model.MapOperationStatus
import com.wu.nearbycheckin.core.model.MapProvider
import com.wu.nearbycheckin.core.model.MovementClass
import com.wu.nearbycheckin.core.model.ProviderAutomationState
import com.wu.nearbycheckin.core.model.ProviderCheckInTask
import com.wu.nearbycheckin.core.model.ProviderPlaceCandidate
import com.wu.nearbycheckin.core.model.ProviderPlaceKey
import com.wu.nearbycheckin.core.model.ProviderTaskStatus


/**
 * 负责 Provider scoped 自动化模型的确定性 JSON 转换。
 */
internal object ProviderAutomationJsonCodec {

    // 将 Provider 候选集合编码为 JSON 数组。
    fun encodeCandidates(
        candidates: List<ProviderPlaceCandidate>
    ): JSONArray {
        return JSONArray().also { array ->
            candidates.forEach { candidate ->
                array.put(candidate.toJsonObject())
            }
        }
    }

    // 从 JSON 数组恢复合法 Provider 候选集合。
    fun decodeCandidates(array: JSONArray?): List<ProviderPlaceCandidate> {
        return array.mapObjects { value -> value.toProviderCandidate() }
    }

    // 将 Provider 任务集合编码为 JSON 数组。
    fun encodeTasks(tasks: List<ProviderCheckInTask>): JSONArray {
        return JSONArray().also { array ->
            tasks.forEach { task -> array.put(task.toJsonObject()) }
        }
    }

    // 从 JSON 数组恢复合法 Provider 任务集合。
    fun decodeTasks(array: JSONArray?): List<ProviderCheckInTask> {
        return array.mapObjects { value -> value.toProviderTask() }
    }

    // 将 Provider 永久完成键编码为稳定字符串数组。
    fun encodeCompletedKeys(keys: Set<ProviderPlaceKey>): JSONArray {
        return JSONArray().also { array ->
            keys.map(ProviderPlaceKey::serialized)
                .sorted()
                .forEach(array::put)
        }
    }

    // 从字符串数组恢复 Provider 永久完成键。
    fun decodeCompletedKeys(array: JSONArray?): Set<ProviderPlaceKey> {
        if (array == null) {
            return emptySet()
        }
        return buildSet {
            for (index in 0 until array.length()) {
                ProviderPlaceKey.parse(array.optString(index))?.let(::add)
            }
        }
    }

    // 将 Provider 运行状态编码为 JSON 对象。
    fun encodeRuntime(runtime: DualMapRuntimeState): JSONObject {
        return JSONObject().apply {
            put(BAIDU_STATE, runtime.baiduState.name)
            put(AMAP_STATE, runtime.amapState.name)
            put(ACTIVE_PROVIDER, runtime.activeProvider?.name.orEmpty())
            put(
                LATEST_LOCATION,
                runtime.latestLocation?.toJsonObject() ?: JSONObject.NULL
            )
            put(BAIDU_LIST_STALE, runtime.baiduListStale)
            put(AMAP_LIST_STALE, runtime.amapListStale)
            put(BAIDU_LIST_FINGERPRINT, runtime.baiduListFingerprint)
            put(AMAP_LIST_FINGERPRINT, runtime.amapListFingerprint)
            put(LAST_PLUGIN_PAGE, runtime.lastPluginPage)
            put(LAST_ACTION, runtime.lastAction)
            put(LAST_EVIDENCE, runtime.lastEvidence)
            put(BAIDU_PLUGIN_PAGE, runtime.baiduPluginPage)
            put(BAIDU_PLUGIN_ACTION, runtime.baiduPluginAction)
            put(BAIDU_PLUGIN_EVIDENCE, runtime.baiduPluginEvidence)
            put(AMAP_PLUGIN_PAGE, runtime.amapPluginPage)
            put(AMAP_PLUGIN_ACTION, runtime.amapPluginAction)
            put(AMAP_PLUGIN_EVIDENCE, runtime.amapPluginEvidence)
            put(
                BAIDU_VERIFIED_SUCCESS_EPOCH_DAY,
                runtime.baiduVerifiedSuccessEpochDay
            )
            put(
                BAIDU_DAILY_VERIFIED_SUCCESS_COUNT,
                runtime.baiduDailyVerifiedSuccessCount
            )
            put(
                BAIDU_TOTAL_VERIFIED_SUCCESS_COUNT,
                runtime.baiduTotalVerifiedSuccessCount
            )
            put(
                BAIDU_OBSERVED_CHECK_IN_COUNT,
                runtime.baiduObservedCheckInCount
            )
            put(
                BAIDU_LAST_VERIFIED_SUCCESS_AT_MILLIS,
                runtime.baiduLastVerifiedSuccessAtMillis
            )
            put(
                BAIDU_LAST_VERIFIED_SUCCESS_EVIDENCE,
                runtime.baiduLastVerifiedSuccessEvidence
            )
            put(
                BAIDU_DAILY_QUOTA_REACHED_AT_MILLIS,
                runtime.baiduDailyQuotaReachedAtMillis
            )
            put(
                BAIDU_DAILY_QUOTA_RESET_AT_MILLIS,
                runtime.baiduDailyQuotaResetAtMillis
            )
            put(
                BAIDU_DAILY_QUOTA_EVIDENCE,
                runtime.baiduDailyQuotaEvidence
            )
        }
    }

    // 从 JSON 对象恢复 Provider 运行状态并保留安全默认值。
    fun decodeRuntime(value: JSONObject?): DualMapRuntimeState {
        if (value == null) {
            return DualMapRuntimeState()
        }
        return DualMapRuntimeState(
            baiduState = value.enumValueOrDefault(
                BAIDU_STATE,
                ProviderAutomationState.IDLE
            ),
            amapState = value.enumValueOrDefault(
                AMAP_STATE,
                ProviderAutomationState.READY
            ),
            activeProvider = value.enumValueOrNull(ACTIVE_PROVIDER),
            latestLocation = value.optJSONObject(LATEST_LOCATION)
                ?.toLocationSnapshot(),
            baiduListStale = value.optBoolean(BAIDU_LIST_STALE, true),
            amapListStale = value.optBoolean(AMAP_LIST_STALE, true),
            baiduListFingerprint = value.optString(
                BAIDU_LIST_FINGERPRINT
            ),
            amapListFingerprint = value.optString(
                AMAP_LIST_FINGERPRINT
            ),
            lastPluginPage = value.optString(LAST_PLUGIN_PAGE),
            lastAction = value.optString(LAST_ACTION),
            lastEvidence = value.optString(LAST_EVIDENCE),
            baiduPluginPage = value.optString(BAIDU_PLUGIN_PAGE),
            baiduPluginAction = value.optString(BAIDU_PLUGIN_ACTION),
            baiduPluginEvidence = value.optString(BAIDU_PLUGIN_EVIDENCE),
            amapPluginPage = value.optString(AMAP_PLUGIN_PAGE),
            amapPluginAction = value.optString(AMAP_PLUGIN_ACTION),
            amapPluginEvidence = value.optString(AMAP_PLUGIN_EVIDENCE),
            baiduVerifiedSuccessEpochDay = value.optLong(
                BAIDU_VERIFIED_SUCCESS_EPOCH_DAY
            ),
            baiduDailyVerifiedSuccessCount = value.optInt(
                BAIDU_DAILY_VERIFIED_SUCCESS_COUNT
            ),
            baiduTotalVerifiedSuccessCount = value.optInt(
                BAIDU_TOTAL_VERIFIED_SUCCESS_COUNT
            ),
            baiduObservedCheckInCount = value.optInt(
                BAIDU_OBSERVED_CHECK_IN_COUNT
            ),
            baiduLastVerifiedSuccessAtMillis = value.optLong(
                BAIDU_LAST_VERIFIED_SUCCESS_AT_MILLIS
            ),
            baiduLastVerifiedSuccessEvidence = value.optString(
                BAIDU_LAST_VERIFIED_SUCCESS_EVIDENCE
            ),
            baiduDailyQuotaReachedAtMillis = value.optLong(
                BAIDU_DAILY_QUOTA_REACHED_AT_MILLIS
            ),
            baiduDailyQuotaResetAtMillis = value.optLong(
                BAIDU_DAILY_QUOTA_RESET_AT_MILLIS
            ),
            baiduDailyQuotaEvidence = value.optString(
                BAIDU_DAILY_QUOTA_EVIDENCE
            )
        )
    }

    // 将 Provider 候选编码为 JSON 对象。
    private fun ProviderPlaceCandidate.toJsonObject(): JSONObject {
        return JSONObject().apply {
            put(PROVIDER, provider.name)
            put(NAME, name)
            put(ADDRESS, address)
            put(DISTANCE_TEXT, distanceText)
            put(COMPLETED, completed)
            put(FINGERPRINT, fingerprint)
            put(OBSERVED_AT_MILLIS, observedAtMillis)
            put(LATITUDE, latitude ?: JSONObject.NULL)
            put(LONGITUDE, longitude ?: JSONObject.NULL)
            put(STABLE_HINT, stableHint)
        }
    }

    // 从 JSON 对象恢复 Provider 候选。
    private fun JSONObject.toProviderCandidate(): ProviderPlaceCandidate? {
        val provider = enumValueOrNull<MapProvider>(PROVIDER) ?: return null
        val name = optString(NAME)
        val fingerprint = optString(FINGERPRINT)
        if (name.isBlank() || fingerprint.isBlank()) {
            return null
        }
        return ProviderPlaceCandidate(
            provider = provider,
            name = name,
            address = optString(ADDRESS),
            distanceText = optString(DISTANCE_TEXT),
            completed = optBoolean(COMPLETED),
            fingerprint = fingerprint,
            observedAtMillis = optLong(OBSERVED_AT_MILLIS),
            latitude = optionalDouble(LATITUDE),
            longitude = optionalDouble(LONGITUDE),
            stableHint = optString(STABLE_HINT)
        )
    }

    // 将 Provider 地点键编码为 JSON 对象。
    private fun ProviderPlaceKey.toJsonObject(): JSONObject {
        return JSONObject().apply {
            put(PROVIDER, provider.name)
            put(NORMALIZED_NAME, normalizedName)
            put(NORMALIZED_ADDRESS, normalizedAddress)
            put(GEO_BUCKET, geoBucket)
            put(STABLE_HINT, stableHint)
        }
    }

    // 从 JSON 对象恢复 Provider 地点键。
    private fun JSONObject.toProviderPlaceKey(): ProviderPlaceKey? {
        val provider = enumValueOrNull<MapProvider>(PROVIDER) ?: return null
        return ProviderPlaceKey(
            provider = provider,
            normalizedName = optString(NORMALIZED_NAME),
            normalizedAddress = optString(NORMALIZED_ADDRESS),
            geoBucket = optString(GEO_BUCKET),
            stableHint = optString(STABLE_HINT)
        )
    }

    // 将 Provider 任务编码为 JSON 对象。
    private fun ProviderCheckInTask.toJsonObject(): JSONObject {
        return JSONObject().apply {
            put(TASK_ID, taskId)
            put(SESSION_ID, sessionId)
            put(PROVIDER, provider.name)
            put(CANDIDATE, candidate.toJsonObject())
            put(CANDIDATE_KEY, candidateKey.toJsonObject())
            put(STATUS, status.name)
            put(ATTEMPTS, attempts)
            put(COMMAND_ID, commandId)
            put(CREATED_AT_MILLIS, createdAtMillis)
            put(UPDATED_AT_MILLIS, updatedAtMillis)
            put(AVAILABLE_AT_MILLIS, availableAtMillis)
            put(LEASE_OWNER, leaseOwner)
            put(LEASE_UNTIL_MILLIS, leaseUntilMillis)
            put(LAST_ERROR, lastError)
            put(EVIDENCE, evidence?.toJsonObject() ?: JSONObject.NULL)
        }
    }

    // 从 JSON 对象恢复 Provider 任务。
    private fun JSONObject.toProviderTask(): ProviderCheckInTask? {
        val taskId = optString(TASK_ID)
        val candidate = optJSONObject(CANDIDATE)
            ?.toProviderCandidate() ?: return null
        val candidateKey = optJSONObject(CANDIDATE_KEY)
            ?.toProviderPlaceKey() ?: return null
        val provider = enumValueOrNull<MapProvider>(PROVIDER) ?: return null
        val status = enumValueOrNull<ProviderTaskStatus>(STATUS) ?: return null
        if (taskId.isBlank() || candidate.provider != provider) {
            return null
        }
        return ProviderCheckInTask(
            taskId = taskId,
            sessionId = optString(SESSION_ID),
            provider = provider,
            candidate = candidate,
            candidateKey = candidateKey,
            status = status,
            attempts = optInt(ATTEMPTS),
            commandId = optString(COMMAND_ID),
            createdAtMillis = optLong(CREATED_AT_MILLIS),
            updatedAtMillis = optLong(UPDATED_AT_MILLIS),
            availableAtMillis = optLong(AVAILABLE_AT_MILLIS),
            leaseOwner = optString(LEASE_OWNER),
            leaseUntilMillis = optLong(LEASE_UNTIL_MILLIS),
            lastError = optString(LAST_ERROR),
            evidence = optJSONObject(EVIDENCE)?.toOperationEvidence()
        )
    }

    // 将插件操作证据编码为 JSON 对象。
    private fun MapOperationEvidence.toJsonObject(): JSONObject {
        return JSONObject().apply {
            put(PROVIDER, provider.name)
            put(TASK_ID, taskId)
            put(COMMAND_ID, commandId)
            put(PROFILE_VERSION, profileVersion)
            put(PAGE, page)
            put(MATCHED_TEXT, matchedText)
            put(VIEW_ID, viewId)
            put(BEFORE_FINGERPRINT, beforeFingerprint)
            put(AFTER_FINGERPRINT, afterFingerprint)
            put(RESULT_STATUS, resultStatus.name)
            put(MESSAGE, message)
            put(OBSERVED_AT_MILLIS, observedAtMillis)
            put(BEFORE_CHECK_IN_COUNT, beforeCheckInCount ?: JSONObject.NULL)
            put(AFTER_CHECK_IN_COUNT, afterCheckInCount ?: JSONObject.NULL)
        }
    }

    // 从 JSON 对象恢复插件操作证据。
    private fun JSONObject.toOperationEvidence(): MapOperationEvidence? {
        val provider = enumValueOrNull<MapProvider>(PROVIDER) ?: return null
        val result = enumValueOrNull<MapOperationStatus>(RESULT_STATUS)
            ?: return null
        return MapOperationEvidence(
            provider = provider,
            taskId = optString(TASK_ID),
            commandId = optString(COMMAND_ID),
            profileVersion = optString(PROFILE_VERSION),
            page = optString(PAGE),
            matchedText = optString(MATCHED_TEXT),
            viewId = optString(VIEW_ID),
            beforeFingerprint = optString(BEFORE_FINGERPRINT),
            afterFingerprint = optString(AFTER_FINGERPRINT),
            resultStatus = result,
            message = optString(MESSAGE),
            observedAtMillis = optLong(OBSERVED_AT_MILLIS),
            beforeCheckInCount = optionalInt(BEFORE_CHECK_IN_COUNT),
            afterCheckInCount = optionalInt(AFTER_CHECK_IN_COUNT)
        )
    }

    // 返回存在且非空的可选整数值。
    private fun JSONObject.optionalInt(key: String): Int? {
        if (isNull(key) || !has(key)) {
            return null
        }
        return optInt(key)
    }

    // 将位置快照编码为 JSON 对象。
    private fun LocationSnapshot.toJsonObject(): JSONObject {
        return JSONObject().apply {
            put(LATITUDE, latitude)
            put(LONGITUDE, longitude)
            put(ACCURACY_METERS, accuracyMeters.toDouble())
            put(SPEED_KMH, speedKmh.toDouble())
            put(TIMESTAMP_MILLIS, timestampMillis)
            put(MOVEMENT_CLASS, movementClass.name)
        }
    }

    // 从 JSON 对象恢复位置快照。
    private fun JSONObject.toLocationSnapshot(): LocationSnapshot? {
        val movement = enumValueOrDefault(
            MOVEMENT_CLASS,
            MovementClass.UNKNOWN
        )
        return LocationSnapshot(
            latitude = optDouble(LATITUDE),
            longitude = optDouble(LONGITUDE),
            accuracyMeters = optDouble(ACCURACY_METERS).toFloat(),
            speedKmh = optDouble(SPEED_KMH).toFloat(),
            timestampMillis = optLong(TIMESTAMP_MILLIS),
            movementClass = movement
        ).takeIf(LocationSnapshot::isUsable)
    }

    // 返回非空且有限的可选双精度值。
    private fun JSONObject.optionalDouble(key: String): Double? {
        if (isNull(key) || !has(key)) {
            return null
        }
        return optDouble(key).takeIf(Double::isFinite)
    }

    // 将 JSON 对象数组映射为非空领域对象列表。
    private inline fun <T> JSONArray?.mapObjects(
        transform: (JSONObject) -> T?
    ): List<T> {
        if (this == null) {
            return emptyList()
        }
        return buildList {
            for (index in 0 until length()) {
                optJSONObject(index)?.let(transform)?.let(::add)
            }
        }
    }

    // 安全读取指定枚举，非法值返回空值。
    private inline fun <reified T : Enum<T>> JSONObject.enumValueOrNull(
        key: String
    ): T? {
        return runCatching { enumValueOf<T>(optString(key)) }.getOrNull()
    }

    // 安全读取指定枚举，非法值返回默认值。
    private inline fun <reified T : Enum<T>> JSONObject.enumValueOrDefault(
        key: String,
        default: T
    ): T {
        return enumValueOrNull<T>(key) ?: default
    }

    private const val PROVIDER = "provider"
    private const val NAME = "name"
    private const val ADDRESS = "address"
    private const val DISTANCE_TEXT = "distanceText"
    private const val COMPLETED = "completed"
    private const val FINGERPRINT = "fingerprint"
    private const val OBSERVED_AT_MILLIS = "observedAtMillis"
    private const val LATITUDE = "latitude"
    private const val LONGITUDE = "longitude"
    private const val STABLE_HINT = "stableHint"
    private const val NORMALIZED_NAME = "normalizedName"
    private const val NORMALIZED_ADDRESS = "normalizedAddress"
    private const val GEO_BUCKET = "geoBucket"
    private const val TASK_ID = "taskId"
    private const val SESSION_ID = "sessionId"
    private const val CANDIDATE = "candidate"
    private const val CANDIDATE_KEY = "candidateKey"
    private const val STATUS = "status"
    private const val ATTEMPTS = "attempts"
    private const val COMMAND_ID = "commandId"
    private const val CREATED_AT_MILLIS = "createdAtMillis"
    private const val UPDATED_AT_MILLIS = "updatedAtMillis"
    private const val AVAILABLE_AT_MILLIS = "availableAtMillis"
    private const val LEASE_OWNER = "leaseOwner"
    private const val LEASE_UNTIL_MILLIS = "leaseUntilMillis"
    private const val LAST_ERROR = "lastError"
    private const val EVIDENCE = "evidence"
    private const val PROFILE_VERSION = "profileVersion"
    private const val PAGE = "page"
    private const val MATCHED_TEXT = "matchedText"
    private const val VIEW_ID = "viewId"
    private const val BEFORE_FINGERPRINT = "beforeFingerprint"
    private const val AFTER_FINGERPRINT = "afterFingerprint"
    private const val RESULT_STATUS = "resultStatus"
    private const val MESSAGE = "message"
    private const val ACCURACY_METERS = "accuracyMeters"
    private const val SPEED_KMH = "speedKmh"
    private const val TIMESTAMP_MILLIS = "timestampMillis"
    private const val MOVEMENT_CLASS = "movementClass"
    private const val BAIDU_STATE = "baiduState"
    private const val AMAP_STATE = "amapState"
    private const val ACTIVE_PROVIDER = "activeProvider"
    private const val LATEST_LOCATION = "latestLocation"
    private const val BAIDU_LIST_STALE = "baiduListStale"
    private const val AMAP_LIST_STALE = "amapListStale"
    private const val BAIDU_LIST_FINGERPRINT = "baiduListFingerprint"
    private const val AMAP_LIST_FINGERPRINT = "amapListFingerprint"
    private const val LAST_PLUGIN_PAGE = "lastPluginPage"
    private const val LAST_ACTION = "lastAction"
    private const val LAST_EVIDENCE = "lastEvidence"
    private const val BAIDU_PLUGIN_PAGE = "baiduPluginPage"
    private const val BAIDU_PLUGIN_ACTION = "baiduPluginAction"
    private const val BAIDU_PLUGIN_EVIDENCE = "baiduPluginEvidence"
    private const val AMAP_PLUGIN_PAGE = "amapPluginPage"
    private const val AMAP_PLUGIN_ACTION = "amapPluginAction"
    private const val AMAP_PLUGIN_EVIDENCE = "amapPluginEvidence"
    private const val BAIDU_VERIFIED_SUCCESS_EPOCH_DAY =
        "baiduVerifiedSuccessEpochDay"
    private const val BAIDU_DAILY_VERIFIED_SUCCESS_COUNT =
        "baiduDailyVerifiedSuccessCount"
    private const val BAIDU_TOTAL_VERIFIED_SUCCESS_COUNT =
        "baiduTotalVerifiedSuccessCount"
    private const val BAIDU_OBSERVED_CHECK_IN_COUNT =
        "baiduObservedCheckInCount"
    private const val BAIDU_LAST_VERIFIED_SUCCESS_AT_MILLIS =
        "baiduLastVerifiedSuccessAtMillis"
    private const val BAIDU_LAST_VERIFIED_SUCCESS_EVIDENCE =
        "baiduLastVerifiedSuccessEvidence"
    private const val BAIDU_DAILY_QUOTA_REACHED_AT_MILLIS =
        "baiduDailyQuotaReachedAtMillis"
    private const val BAIDU_DAILY_QUOTA_RESET_AT_MILLIS =
        "baiduDailyQuotaResetAtMillis"
    private const val BAIDU_DAILY_QUOTA_EVIDENCE =
        "baiduDailyQuotaEvidence"
    private const val BEFORE_CHECK_IN_COUNT = "beforeCheckInCount"
    private const val AFTER_CHECK_IN_COUNT = "afterCheckInCount"
}
