// @Time: 2026/06/29 07:06
// @Author: MrWu
// @File: CheckInRecord.kt
// @Software: Android Studio


@file:Suppress("SpellCheckingInspection")


package com.wu.nearbycheckin

/**
 * 表示一次已经确认完成的周边地点自动打卡记录。
 *
 * @property id 打卡记录的唯一标识。
 * @property poiUid 百度地图地点唯一标识。
 * @property poiName 打卡地点名称。
 * @property address 打卡地点地址。
 * @property category 地点分类。
 * @property latitude 打卡地点的百度坐标纬度。
 * @property longitude 打卡地点的百度坐标经度。
 * @property distanceMeters 打卡时与地点之间的距离，单位为米。
 * @property stayDurationMillis 在该地点持续停留的时间，单位为毫秒。
 * @property checkInTimeMillis 完成打卡时的时间戳，单位为毫秒。
 */
data class CheckInRecord(
    val id: String,
    val poiUid: String,
    val poiName: String,
    val address: String,
    val category: String,
    val latitude: Double,
    val longitude: Double,
    val distanceMeters: Int,
    val stayDurationMillis: Long,
    val checkInTimeMillis: Long
)
