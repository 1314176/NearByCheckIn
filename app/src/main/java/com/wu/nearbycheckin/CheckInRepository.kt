// @Time: 2026/06/29 07:20
// @Author: MrWu
// @File: CheckInRepository.kt
// @Software: Android Studio


@file:Suppress("SpellCheckingInspection")

package com.wu.nearbycheckin


import java.util.UUID

import android.content.Context
import android.util.Log

import androidx.core.content.edit

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
    }
}


