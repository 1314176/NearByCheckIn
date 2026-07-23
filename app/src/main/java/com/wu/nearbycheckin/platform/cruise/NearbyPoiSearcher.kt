// @Time: 2026/06/29 06:10
// @Author: MrWu
// @File: NearbyPoiSearcher.kt
// @Software: Android Studio


@file:Suppress("SpellCheckingInspection")

package com.wu.nearbycheckin.platform.cruise


import kotlin.math.roundToInt

import android.util.Log

import com.baidu.mapapi.model.LatLng
import com.baidu.mapapi.search.core.SearchResult
import com.baidu.mapapi.search.poi.OnGetPoiSearchResultListener
import com.baidu.mapapi.search.poi.PoiDetailSearchResult
import com.baidu.mapapi.search.poi.PoiIndoorResult
import com.baidu.mapapi.search.poi.PoiNearbySearchOption
import com.baidu.mapapi.search.poi.PoiResult
import com.baidu.mapapi.search.poi.PoiSearch
import com.baidu.mapapi.utils.CoordinateConverter
import com.baidu.mapapi.utils.DistanceUtil

/**
 * 保存一次百度地图周边POI检索返回的地点信息。
 *
 * @property uid 百度地图地点唯一标识。
 * @property name 地点名称。
 * @property address 地点地址。
 * @property category 地点分类标签。
 * @property latitude 百度BD09LL坐标纬度。
 * @property longitude 百度BD09LL坐标经度。
 * @property distanceMeters 地点与检索中心点之间的直线距离，单位为米。
 */
data class NearbyPoi(
    val uid: String,
    val name: String,
    val address: String,
    val category: String,
    val latitude: Double,
    val longitude: Double,
    val distanceMeters: Int
)

/**
 * 负责将Android系统定位坐标转换成百度坐标，并检索指定范围内的周边地点。
 *
 * @param onSearchSuccess 检索成功后返回按照距离从近到远排列的地点列表。
 * @param onSearchFailure 检索失败后返回对应的中文错误信息。
 */
class NearbyPoiSearcher(
    private val onSearchSuccess: (List<NearbyPoi>) -> Unit,
    private val onSearchFailure: (String) -> Unit
) : OnGetPoiSearchResultListener {

    private val poiSearch: PoiSearch = PoiSearch.newInstance()

    private var currentSearchCenter: LatLng? = null
    private var isDestroyed = false

    init {
        // 百度官方要求在发起检索之前注册结果监听器
        poiSearch.setOnGetPoiSearchResultListener(this)
    }

    /**
     * 根据Android系统返回的原始经纬度检索附近地点。
     *
     * @param latitude Android系统定位返回的原始纬度。
     * @param longitude Android系统定位返回的原始经度。
     * @param radiusMeters 周边地点检索半径，默认200米。
     * @param keyword 地点检索关键词，多个关键词使用美元符号分隔。
     * @return 成功发送异步检索请求时返回true，否则返回false。
     */
    fun searchNearby(
        latitude: Double,
        longitude: Double,
        radiusMeters: Int = DEFAULT_SEARCH_RADIUS_METERS,
        keyword: String = defaultSearchKeywords
    ): Boolean {
        if (isDestroyed) {
            onSearchFailure("POI检索器已经释放，无法继续检索")
            return false
        }

        if (
            latitude !in MIN_LATITUDE..MAX_LATITUDE ||
            longitude !in MIN_LONGITUDE..MAX_LONGITUDE
        ) {
            onSearchFailure("当前位置经纬度无效，无法检索周边地点")
            return false
        }

        val originalLocation = LatLng(
            latitude,
            longitude
        )

        val baiduLocation = CoordinateConverter()
            .from(CoordinateConverter.CoordType.GPS)
            .coord(originalLocation)
            .convert()

        if (baiduLocation == null) {
            onSearchFailure("原始坐标转换为百度坐标失败")
            return false
        }

        currentSearchCenter = baiduLocation

        val safeRadius = radiusMeters.coerceIn(
            MIN_SEARCH_RADIUS_METERS,
            MAX_SEARCH_RADIUS_METERS
        )

        val safeKeyword = keyword.ifBlank {
            defaultSearchKeywords
        }

        val searchOption = PoiNearbySearchOption()
            .location(baiduLocation)
            .radius(safeRadius)
            .radiusLimit(true)
            .keyword(safeKeyword)
            .pageNum(FIRST_PAGE_NUMBER)
            .pageCapacity(MAX_RESULT_COUNT)

        Log.d(
            TAG,
            "发送POI检索请求：半径${safeRadius}米，关键词：$safeKeyword"
        )

        val requestStarted = poiSearch.searchNearby(searchOption)

        if (!requestStarted) {
            onSearchFailure("百度地图周边地点检索请求发送失败")
        }

        return requestStarted
    }

    /**
     * 接收百度地图返回的POI周边检索结果，并转换为项目内部地点数据。
     *
     * @param result 百度地图返回的POI检索结果。
     */
    override fun onGetPoiResult(result: PoiResult?) {
        if (result == null) {
            onSearchFailure("百度地图没有返回POI检索结果")
            return
        }

        if (result.error != SearchResult.ERRORNO.NO_ERROR) {
            val errorMessage = "POI检索失败：${result.error}"

            Log.e(
                TAG,
                errorMessage
            )

            onSearchFailure(errorMessage)
            return
        }

        val searchCenter = currentSearchCenter

        if (searchCenter == null) {
            onSearchFailure("POI检索中心点不存在")
            return
        }

        val nearbyPoiList = result.allPoi
            .orEmpty()
            .mapNotNull { poiInfo ->
                val poiLocation = poiInfo.location
                    ?: return@mapNotNull null

                val distance = DistanceUtil.getDistance(
                    searchCenter,
                    poiLocation
                )

                NearbyPoi(
                    uid = poiInfo.uid.orEmpty(),
                    name = poiInfo.name.orEmpty(),
                    address = poiInfo.address.orEmpty(),
                    category = poiInfo.tag.orEmpty(),
                    latitude = poiLocation.latitude,
                    longitude = poiLocation.longitude,
                    distanceMeters = distance
                        .coerceAtLeast(MIN_DISTANCE_METERS)
                        .roundToInt()
                )
            }
            .filter { nearbyPoi ->
                nearbyPoi.name.isNotBlank()
            }
            .sortedBy { nearbyPoi ->
                nearbyPoi.distanceMeters
            }

        Log.d(
            TAG,
            "POI检索成功，共返回${nearbyPoiList.size}个有效地点"
        )

        nearbyPoiList.forEach { nearbyPoi ->
            Log.d(
                TAG,
                "地点：${nearbyPoi.name}，" +
                        "距离：${nearbyPoi.distanceMeters}米，" +
                        "分类：${nearbyPoi.category}"
            )
        }

        onSearchSuccess(nearbyPoiList)
    }

    /**
     * 接收新版POI详情检索结果。
     *
     * 当前版本暂时不使用地点详情检索，但必须实现监听器中的对应方法。
     *
     * @param result 百度地图返回的新版POI详情结果。
     */
    override fun onGetPoiDetailResult(
        result: PoiDetailSearchResult?
    ) = Unit

    /**
     * 接收百度地图旧版POI详情检索结果。
     *
     * 该回调已经被百度地图SDK废弃，仅为了兼容监听器接口而保留。
     *
     * @param result 百度地图返回的旧版POI详情结果。
     */
    @Deprecated("百度地图旧版回调，仅用于兼容SDK接口")
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onGetPoiDetailResult(
        result: com.baidu.mapapi.search.poi.PoiDetailResult?
    ) = Unit

    /**
     * 接收室内POI检索结果。
     *
     * 当前项目暂时不使用室内POI检索，但必须实现监听器中的对应方法。
     *
     * @param result 百度地图返回的室内POI检索结果。
     */
    override fun onGetPoiIndoorResult(
        result: PoiIndoorResult?
    ) = Unit

    /**
     * 释放百度地图POI检索实例，防止服务停止后继续占用相关资源。
     */
    fun destroy() {
        if (isDestroyed) {
            return
        }

        poiSearch.destroy()
        currentSearchCenter = null
        isDestroyed = true

        Log.d(
            TAG,
            "POI检索器已经释放"
        )
    }

    private companion object {
        const val TAG = "NearbyPoiSearcher"

        const val DEFAULT_SEARCH_RADIUS_METERS = 1_000
        const val MIN_SEARCH_RADIUS_METERS = 1_000
        const val MAX_SEARCH_RADIUS_METERS = 5_000
        const val MAX_RESULT_COUNT = 50
        const val FIRST_PAGE_NUMBER = 0

        const val MIN_LATITUDE = -90.0
        const val MAX_LATITUDE = 90.0
        const val MIN_LONGITUDE = -180.0
        const val MAX_LONGITUDE = 180.0
        const val MIN_DISTANCE_METERS = 0.0
        private const val KEYWORD_SEPARATOR = '$'
        private val defaultSearchKeywords = listOf(
            "商场",
            "景点",
            "小区",
            "车站",
            "餐厅",
            "酒店",
            "医院",
            "学校",
            "停车场",
            "公园"
        ).joinToString(
            separator = KEYWORD_SEPARATOR.toString()
        )
    }
}

