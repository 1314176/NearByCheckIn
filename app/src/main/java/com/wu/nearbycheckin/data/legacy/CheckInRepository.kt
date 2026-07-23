// @Time: 2026/06/29 07:20
// @Author: MrWu
// @File: CheckInRepository.kt
// @Software: Android Studio


@file:Suppress("SpellCheckingInspection")

package com.wu.nearbycheckin.data.legacy


import java.util.UUID

import android.content.Context
import android.util.Log

import androidx.core.content.edit

import com.wu.nearbycheckin.domain.model.CheckInRecord
import com.wu.nearbycheckin.domain.model.DualMapCheckInRecord
import com.wu.nearbycheckin.domain.model.MapOperationResult
import com.wu.nearbycheckin.domain.model.MapOperationStatus
import com.wu.nearbycheckin.domain.model.MapProvider
import com.wu.nearbycheckin.platform.cruise.NearbyPoi

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 负责将自动打卡记录保存到手机本地，并提供记录查询和重复判断能力。
 *
 * @param context 当前应用上下文。
 */
class CheckInRepository(
    context: Context
) {

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    init {
        migrateCompletedPoiKeysIfNeeded()
    }

    /**
     * 保存一条新打卡记录。
     *
     * @param record 需要保存的打卡记录。
     * @return 成功写入本地存储时返回true，否则返回false。
     */
    @Synchronized
    fun saveRecord(record: CheckInRecord): Boolean {
        return try {
            val currentRecords = getAllRecords().toMutableList()

            currentRecords.add(
                index = 0,
                element = record
            )

            val recordsJsonArray = JSONArray()

            currentRecords.forEach { checkInRecord ->
                recordsJsonArray.put(
                    checkInRecord.toJsonObject()
                )
            }

            val completedPoiKeys = getCompletedPoiKeys().toMutableSet()
            if (record.poiUid.isNotBlank()) {
                completedPoiKeys.add(record.poiUid)
            }

            val saved = preferences.edit()
                .putString(
                    RECORDS_KEY,
                    recordsJsonArray.toString()
                )
                .putStringSet(
                    COMPLETED_POI_KEYS_KEY,
                    completedPoiKeys
                )
                .commit()

            if (!saved) {
                Log.e(
                    TAG,
                    "打卡记录保存失败：本地存储提交未成功"
                )

                return false
            }

            Log.d(
                TAG,
                "打卡记录保存成功：${record.poiName}"
            )

            true
        } catch (exception: JSONException) {
            Log.e(
                TAG,
                "打卡记录保存失败",
                exception
            )

            false
        }
    }

    /**
     * 创建并保存一条新打卡记录。
     *
     * @param poi 当前需要打卡的百度地图地点。
     * @param stayDurationMillis 在该地点持续停留的时间，单位为毫秒。
     * @param checkInTimeMillis 完成打卡时的时间戳，默认使用当前系统时间。
     * @return 保存成功时返回创建后的记录，保存失败时返回null。
     */
    fun createAndSaveRecord(
        poi: NearbyPoi,
        stayDurationMillis: Long,
        checkInTimeMillis: Long = System.currentTimeMillis()
    ): CheckInRecord? {
        val record = CheckInRecord(
            id = UUID.randomUUID().toString(),
            poiUid = poi.uid,
            poiName = poi.name,
            address = poi.address,
            category = poi.category,
            latitude = poi.latitude,
            longitude = poi.longitude,
            distanceMeters = poi.distanceMeters,
            stayDurationMillis = stayDurationMillis,
            checkInTimeMillis = checkInTimeMillis
        )

        return if (saveRecord(record)) {
            record
        } else {
            null
        }
    }

    /**
     * 读取手机本地保存的全部打卡记录，并按照时间从新到旧排列。
     *
     * @return 成功解析的打卡记录列表；没有记录或解析失败时返回空列表。
     */
    @Synchronized
    fun getAllRecords(): List<CheckInRecord> {
        val recordsJson = preferences.getString(
            RECORDS_KEY,
            EMPTY_JSON_ARRAY
        ) ?: EMPTY_JSON_ARRAY

        return try {
            val recordsJsonArray = JSONArray(recordsJson)
            val records = mutableListOf<CheckInRecord>()

            for (index in 0 until recordsJsonArray.length()) {
                val recordJsonObject = recordsJsonArray.optJSONObject(index)
                    ?: continue

                recordJsonObject.toCheckInRecord()?.let { record ->
                    records.add(record)
                }
            }

            records.sortedByDescending { record ->
                record.checkInTimeMillis
            }
        } catch (exception: JSONException) {
            Log.e(
                TAG,
                "读取本地打卡记录失败",
                exception
            )

            emptyList()
        }
    }

    /**
     * 保存一条双地图事务记录。
     *
     * @param record 需要保存的双地图事务记录。
     * @return 成功写入本地存储时返回true，否则返回false。
     */
    @Synchronized
    fun saveDualMapRecord(record: DualMapCheckInRecord): Boolean {
        return try {
            val currentRecords = getAllDualMapRecords().toMutableList()

            currentRecords.add(
                index = 0,
                element = record
            )

            val recordsJsonArray = JSONArray()

            currentRecords.forEach { dualMapRecord ->
                recordsJsonArray.put(
                    dualMapRecord.toJsonObject()
                )
            }

            val saved = preferences.edit()
                .putString(
                    DUAL_MAP_RECORDS_KEY,
                    recordsJsonArray.toString()
                )
                .commit()

            if (!saved) {
                Log.e(
                    TAG,
                    "双地图事务记录保存失败：本地存储提交未成功"
                )

                return false
            }

            Log.d(
                TAG,
                "双地图事务记录保存成功：${record.placeName}"
            )

            true
        } catch (exception: JSONException) {
            Log.e(
                TAG,
                "双地图事务记录保存失败",
                exception
            )

            false
        }
    }

    /**
     * 读取手机本地保存的全部双地图事务记录，并按照创建时间从新到旧排列。
     *
     * @return 成功解析的事务记录列表；没有记录或解析失败时返回空列表。
     */
    @Synchronized
    fun getAllDualMapRecords(): List<DualMapCheckInRecord> {
        val recordsJson = preferences.getString(
            DUAL_MAP_RECORDS_KEY,
            EMPTY_JSON_ARRAY
        ) ?: EMPTY_JSON_ARRAY

        return try {
            val recordsJsonArray = JSONArray(recordsJson)
            val records = mutableListOf<DualMapCheckInRecord>()

            for (index in 0 until recordsJsonArray.length()) {
                val recordJsonObject = recordsJsonArray.optJSONObject(index)
                    ?: continue

                recordJsonObject.toDualMapCheckInRecord()?.let { record ->
                    records.add(record)
                }
            }

            records.sortedByDescending { record ->
                record.createdAt
            }
        } catch (exception: JSONException) {
            Log.e(
                TAG,
                "读取双地图事务记录失败",
                exception
            )

            emptyList()
        }
    }

    /**
     * 判断指定地点是否曾经完成过打卡，用于跨重启的永久去重。
     *
     * @param poiUid 地点稳定标识。
     * @return 已经完成过打卡时返回true，否则返回false。
     */
    @Synchronized
    fun hasCompletedRecord(poiUid: String): Boolean {
        if (poiUid.isBlank()) {
            return false
        }

        return getCompletedPoiKeys().contains(poiUid)
    }

    /**
     * 清空手机本地保存的全部打卡记录。
     *
     * @return 成功提交清空操作时返回true，否则返回false。
     */
    fun clearAllRecords(): Boolean {
        val cleared = preferences.edit()
            .remove(RECORDS_KEY)
            .remove(DUAL_MAP_RECORDS_KEY)
            .remove(COMPLETED_POI_KEYS_KEY)
            .remove(COMPLETED_POI_KEYS_MIGRATED_KEY)
            .commit()

        if (cleared) {
            Log.d(
                TAG,
                "全部本地打卡记录已清空"
            )
        } else {
            Log.e(
                TAG,
                "清空本地打卡记录失败"
            )
        }

        return cleared
    }

    /**
     * 将打卡记录转换为可保存到本地的JSON对象。
     *
     * @return 包含完整打卡字段的JSON对象。
     */
    private fun CheckInRecord.toJsonObject(): JSONObject {
        return JSONObject().apply {
            put(ID_KEY, id)
            put(POI_UID_KEY, poiUid)
            put(POI_NAME_KEY, poiName)
            put(ADDRESS_KEY, address)
            put(CATEGORY_KEY, category)
            put(LATITUDE_KEY, latitude)
            put(LONGITUDE_KEY, longitude)
            put(DISTANCE_METERS_KEY, distanceMeters)
            put(STAY_DURATION_MILLIS_KEY, stayDurationMillis)
            put(CHECK_IN_TIME_MILLIS_KEY, checkInTimeMillis)
        }
    }

    /**
     * 将双地图事务记录转换为可保存到本地的JSON对象。
     *
     * @return 包含完整双地图事务字段的JSON对象。
     */
    private fun DualMapCheckInRecord.toJsonObject(): JSONObject {
        return JSONObject().apply {
            put(RECORD_ID_KEY, recordId)
            put(SESSION_ID_KEY, sessionId)
            put(UNIFIED_PLACE_ID_KEY, unifiedPlaceId)
            put(PLACE_NAME_KEY, placeName)
            put(PLACE_ADDRESS_KEY, placeAddress)
            put(LOCATION_ACCURACY_KEY, locationAccuracy.toDouble())
            put(SPEED_KMH_KEY, speedKmh.toDouble())
            put(DISTANCE_METERS_KEY, distanceMeters)
            put(BAIDU_RESULT_KEY, baiduResult.toJsonObject())
            put(AMAP_RESULT_KEY, amapResult.toJsonObject())
            put(OVERALL_STATUS_KEY, overallStatus)
            put(CREATED_AT_KEY, createdAt)
            put(COMPLETED_AT_KEY, completedAt)
        }
    }

    /**
     * 将地图端操作结果转换为可保存到本地的JSON对象。
     *
     * @return 包含单端操作结果字段的JSON对象。
     */
    private fun MapOperationResult.toJsonObject(): JSONObject {
        return JSONObject().apply {
            put(PROVIDER_KEY, provider.name)
            put(FLOW_VERSION_KEY, flowVersion)
            put(STATUS_KEY, status.name)
            put(STARTED_AT_KEY, startedAt)
            put(COMPLETED_AT_KEY, completedAt)
            put(ATTEMPTS_KEY, attempts)
            put(MATCHED_PLACE_TEXT_KEY, matchedPlaceText)
            put(MATCHED_NODE_KEY, matchedNode)
            put(FAILURE_CODE_KEY, failureCode)
            put(FAILURE_MESSAGE_KEY, failureMessage)
            put(EVIDENCE_TEXT_KEY, evidenceText)
        }
    }

    /**
     * 将本地JSON对象解析为打卡记录。
     *
     * @return 字段完整时返回打卡记录，解析失败时返回null。
     */
    private fun JSONObject.toCheckInRecord(): CheckInRecord? {
        return try {
            CheckInRecord(
                id = getString(ID_KEY),
                poiUid = optString(POI_UID_KEY),
                poiName = getString(POI_NAME_KEY),
                address = optString(ADDRESS_KEY),
                category = optString(CATEGORY_KEY),
                latitude = getDouble(LATITUDE_KEY),
                longitude = getDouble(LONGITUDE_KEY),
                distanceMeters = getInt(DISTANCE_METERS_KEY),
                stayDurationMillis = getLong(
                    STAY_DURATION_MILLIS_KEY
                ),
                checkInTimeMillis = getLong(
                    CHECK_IN_TIME_MILLIS_KEY
                )
            )
        } catch (exception: JSONException) {
            Log.e(
                TAG,
                "跳过无法解析的打卡记录",
                exception
            )

            null
        }
    }

    /**
     * 将本地JSON对象解析为双地图事务记录。
     *
     * @return 字段完整时返回事务记录，解析失败时返回null。
     */
    private fun JSONObject.toDualMapCheckInRecord(): DualMapCheckInRecord? {
        return try {
            val baiduResultJson = getJSONObject(
                BAIDU_RESULT_KEY
            )
            val amapResultJson = getJSONObject(
                AMAP_RESULT_KEY
            )

            DualMapCheckInRecord(
                recordId = getString(RECORD_ID_KEY),
                sessionId = optString(SESSION_ID_KEY),
                unifiedPlaceId = optString(UNIFIED_PLACE_ID_KEY),
                placeName = getString(PLACE_NAME_KEY),
                placeAddress = optString(PLACE_ADDRESS_KEY),
                locationAccuracy = optDouble(
                    LOCATION_ACCURACY_KEY
                ).toFloat(),
                speedKmh = optDouble(
                    SPEED_KMH_KEY
                ).toFloat(),
                distanceMeters = getInt(DISTANCE_METERS_KEY),
                baiduResult = baiduResultJson.toMapOperationResult()
                    ?: return null,
                amapResult = amapResultJson.toMapOperationResult()
                    ?: return null,
                overallStatus = optString(OVERALL_STATUS_KEY),
                createdAt = getLong(CREATED_AT_KEY),
                completedAt = optLong(COMPLETED_AT_KEY)
            )
        } catch (exception: JSONException) {
            Log.e(
                TAG,
                "跳过无法解析的双地图事务记录",
                exception
            )

            null
        }
    }

    /**
     * 将本地JSON对象解析为单端地图操作结果。
     *
     * @return 字段完整时返回单端结果，解析失败时返回null。
     */
    private fun JSONObject.toMapOperationResult(): MapOperationResult? {
        return try {
            MapOperationResult(
                provider = enumValueOrNull<MapProvider>(
                    getString(PROVIDER_KEY)
                ) ?: return null,
                flowVersion = optString(FLOW_VERSION_KEY),
                status = enumValueOrNull<MapOperationStatus>(
                    getString(STATUS_KEY)
                ) ?: return null,
                startedAt = getLong(STARTED_AT_KEY),
                completedAt = optLong(COMPLETED_AT_KEY),
                attempts = optInt(ATTEMPTS_KEY),
                matchedPlaceText = optString(MATCHED_PLACE_TEXT_KEY),
                matchedNode = optString(MATCHED_NODE_KEY),
                failureCode = optString(FAILURE_CODE_KEY),
                failureMessage = optString(FAILURE_MESSAGE_KEY),
                evidenceText = optString(EVIDENCE_TEXT_KEY)
            )
        } catch (exception: JSONException) {
            Log.e(
                TAG,
                "跳过无法解析的地图端操作结果",
                exception
            )

            null
        }
    }

    /**
     * 将字符串安全转换为指定枚举值。
     *
     * @param value 枚举名称。
     * @return 转换成功时返回枚举值，否则返回null。
     */
    private inline fun <reified T : Enum<T>> enumValueOrNull(value: String): T? {
        return try {
            enumValueOf<T>(
                value
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * 读取已经完成过打卡的地点键集合，返回副本避免外部修改SharedPreferences内部集合。
     */
    private fun getCompletedPoiKeys(): Set<String> {
        return preferences.getStringSet(
            COMPLETED_POI_KEYS_KEY,
            emptySet()
        ).orEmpty().toSet()
    }

    /**
     * 将旧版本仅存于记录列表中的地点键迁移到永久去重集合。
     */
    @Synchronized
    private fun migrateCompletedPoiKeysIfNeeded() {
        if (
            preferences.getBoolean(
                COMPLETED_POI_KEYS_MIGRATED_KEY,
                false
            )
        ) {
            return
        }

        val completedPoiKeys = getAllRecords()
            .map { record ->
                record.poiUid
            }
            .filter { poiUid ->
                poiUid.isNotBlank()
            }
            .toSet()

        preferences.edit {
            putStringSet(
                COMPLETED_POI_KEYS_KEY,
                completedPoiKeys
            )
            putBoolean(
                COMPLETED_POI_KEYS_MIGRATED_KEY,
                true
            )
        }
    }

    private companion object {
        const val TAG = "CheckInRepository"

        const val PREFERENCES_NAME = "check_in_records"
        const val RECORDS_KEY = "records"
        const val DUAL_MAP_RECORDS_KEY = "dual_map_records"
        const val COMPLETED_POI_KEYS_KEY = "completed_poi_keys"
        const val COMPLETED_POI_KEYS_MIGRATED_KEY = "completed_poi_keys_migrated"
        const val EMPTY_JSON_ARRAY = "[]"

        const val ID_KEY = "id"
        const val POI_UID_KEY = "poiUid"
        const val POI_NAME_KEY = "poiName"
        const val ADDRESS_KEY = "address"
        const val CATEGORY_KEY = "category"
        const val LATITUDE_KEY = "latitude"
        const val LONGITUDE_KEY = "longitude"
        const val DISTANCE_METERS_KEY = "distanceMeters"
        const val STAY_DURATION_MILLIS_KEY = "stayDurationMillis"
        const val CHECK_IN_TIME_MILLIS_KEY = "checkInTimeMillis"
        const val RECORD_ID_KEY = "recordId"
        const val SESSION_ID_KEY = "sessionId"
        const val UNIFIED_PLACE_ID_KEY = "unifiedPlaceId"
        const val PLACE_NAME_KEY = "placeName"
        const val PLACE_ADDRESS_KEY = "placeAddress"
        const val LOCATION_ACCURACY_KEY = "locationAccuracy"
        const val SPEED_KMH_KEY = "speedKmh"
        const val BAIDU_RESULT_KEY = "baiduResult"
        const val AMAP_RESULT_KEY = "amapResult"
        const val OVERALL_STATUS_KEY = "overallStatus"
        const val CREATED_AT_KEY = "createdAt"
        const val COMPLETED_AT_KEY = "completedAt"
        const val PROVIDER_KEY = "provider"
        const val FLOW_VERSION_KEY = "flowVersion"
        const val STATUS_KEY = "status"
        const val STARTED_AT_KEY = "startedAt"
        const val ATTEMPTS_KEY = "attempts"
        const val MATCHED_PLACE_TEXT_KEY = "matchedPlaceText"
        const val MATCHED_NODE_KEY = "matchedNode"
        const val FAILURE_CODE_KEY = "failureCode"
        const val FAILURE_MESSAGE_KEY = "failureMessage"
        const val EVIDENCE_TEXT_KEY = "evidenceText"
    }
}


