// @Time: 2026/07/20 18:37
// @Author: MrWu
// @File: CruiseDiscoveryController.kt
// @Software: Android Studio


package com.wu.nearbycheckin.platform.cruise


import java.util.Locale

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log

import androidx.core.content.ContextCompat

import com.wu.nearbycheckin.core.domain.coordinator.ProviderAutomationCoordinator
import com.wu.nearbycheckin.core.domain.coordinator.ProviderMovementPolicy
import com.wu.nearbycheckin.core.domain.coordinator.ProviderRefreshSchedulePolicy
import com.wu.nearbycheckin.core.model.LocationSnapshot
import com.wu.nearbycheckin.core.model.MovementClass


/**
 * 封装定位、POI 搜索、静止心跳和候选标准化平台逻辑。
 */
class CruiseDiscoveryController(
    private val context: Context,
    private val providerCoordinator: ProviderAutomationCoordinator,
    private val onStatusChanged: (String) -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var locationManager: LocationManager
    private lateinit var nearbyPoiSearcher: NearbyPoiSearcher
    private var latestLocation: Location? = null
    private var isPoiSearchRunning = false
    private var lastPoiSearchTimeMillis = 0L
    private var lastPoiSearchLocation: Location? = null
    private var continuousScanScheduled = false
    private val movementPolicy = ProviderMovementPolicy()
    private val refreshSchedulePolicy = ProviderRefreshSchedulePolicy()
    private var latestMovementClass = MovementClass.UNKNOWN

    private val baiduAuthStateListener:
            (Boolean, String) -> Unit = { passed, description ->
        mainHandler.post {
            if (!passed) {
                Log.e(TAG, "百度地图鉴权失败：$description")
                onStatusChanged("百度地图鉴权失败，请检查AK配置")
                return@post
            }

            Log.d(TAG, "百度地图鉴权已通过：$description")
            latestLocation?.let { location ->
                val started = requestNearbyPoiSearchIfNeeded(
                    location = location,
                    forceSearch = true
                )
                if (!started) {
                    scheduleContinuousScan(
                        CONTINUOUS_SCAN_RETRY_DELAY_MILLIS
                    )
                }
            }
        }
    }

    private val continuousScanRunnable = object : Runnable {

        // 静止状态下强制触发下一次 POI 搜索。
        override fun run() {
            continuousScanScheduled = false
            providerCoordinator.heartbeat()
            scheduleContinuousScan()
        }
    }

    private val locationListener = object : LocationListener {

        // 接收真实位置并触发候选发现。
        override fun onLocationChanged(location: Location) {
            updateLocation(location)
        }

        // 记录定位提供器启用状态。
        override fun onProviderEnabled(provider: String) {
            Log.d(TAG, "定位提供器已启用：$provider")
        }

        // 记录定位提供器停用状态。
        override fun onProviderDisabled(provider: String) {
            Log.w(TAG, "定位提供器已关闭：$provider")
        }

        // 兼容旧版 Android 定位状态回调。
        @Deprecated("Android 已废弃此定位状态回调")
        override fun onStatusChanged(
            provider: String?,
            status: Int,
            extras: Bundle?
        ) {
            Log.d(
                TAG,
                "定位状态变化：provider=$provider, status=$status"
            )
        }
    }

    // 初始化百度 SDK、POI 搜索器和系统定位监听。
    fun start(): Boolean {
        BaiduMapSdkManager.addAuthStateListener(
            baiduAuthStateListener
        )
        if (
            !BaiduMapSdkManager.initializeAfterPrivacyConsent(
                context.applicationContext
            )
        ) {
            BaiduMapSdkManager.removeAuthStateListener(
                baiduAuthStateListener
            )
            return false
        }

        initializeNearbyPoiSearcher()
        return startLocationTracking()
    }

    // 停止定位、心跳和 POI 搜索并释放 SDK 监听。
    fun destroy() {
        BaiduMapSdkManager.removeAuthStateListener(
            baiduAuthStateListener
        )
        mainHandler.removeCallbacks(continuousScanRunnable)
        continuousScanScheduled = false
        latestLocation = null

        if (::locationManager.isInitialized) {
            locationManager.removeUpdates(locationListener)
        }
        if (::nearbyPoiSearcher.isInitialized) {
            nearbyPoiSearcher.destroy()
        }
    }

    // 创建周边 POI 搜索器并连接结果回调。
    private fun initializeNearbyPoiSearcher() {
        nearbyPoiSearcher = NearbyPoiSearcher(
            onSearchSuccess = { nearbyPoiList ->
                isPoiSearchRunning = false
                if (nearbyPoiList.isEmpty()) {
                    Log.d(TAG, "当前位置附近没有可提交的地点")
                    onStatusChanged("附近暂未发现地点")
                } else {
                    recordPoiDiagnostics(nearbyPoiList)
                }
                rescheduleContinuousScan()
            },
            onSearchFailure = { errorMessage ->
                isPoiSearchRunning = false
                Log.e(TAG, errorMessage)
                onStatusChanged("地点检索失败，请稍后重试")
                rescheduleContinuousScan()
            }
        )
    }

    // 仅记录 SDK 周边检索诊断，候选任务由地图 UI 列表唯一产生。
    private fun recordPoiDiagnostics(
        nearbyPoiList: List<NearbyPoi>
    ) {
        val nearestPoi = nearbyPoiList.first()
        Log.d(
            TAG,
            "SDK 周边诊断：总数=${nearbyPoiList.size}，" +
                    "最近=${nearestPoi.name}；不创建 Provider 任务"
        )
        onStatusChanged("已更新位置，等待百度地图读取候选")
    }

    // 安排下一次静止心跳扫描。
    private fun scheduleContinuousScan(
        delayMillis: Long = continuousScanIntervalMillis()
    ) {
        if (
            continuousScanScheduled ||
            latestLocation == null
        ) {
            return
        }
        continuousScanScheduled = true
        mainHandler.postDelayed(
            continuousScanRunnable,
            delayMillis
        )
    }

    // 以最新候选结果重新计算并覆盖下一次扫描任务。
    private fun rescheduleContinuousScan() {
        mainHandler.removeCallbacks(continuousScanRunnable)
        continuousScanScheduled = false
        scheduleContinuousScan()
    }

    // 根据当前速度返回静止心跳间隔。
    private fun continuousScanIntervalMillis(): Long {
        return refreshSchedulePolicy.nextIntervalMillis(
            movementClass = latestMovementClass,
            currentRangeExhausted = false
        )
    }

    // 检查权限并启动 GPS 与网络定位监听。
    @SuppressLint("MissingPermission")
    private fun startLocationTracking(): Boolean {
        val fineGranted = hasPermission(
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        val coarseGranted = hasPermission(
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (!fineGranted && !coarseGranted) {
            Log.e(TAG, "定位权限尚未授予")
            return false
        }

        locationManager =
            context.getSystemService(LocationManager::class.java)
        var providerStarted = false
        if (
            fineGranted &&
            locationManager.isProviderEnabled(
                LocationManager.GPS_PROVIDER
            )
        ) {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                LOCATION_UPDATE_INTERVAL_MILLIS,
                LOCATION_UPDATE_DISTANCE_METERS,
                locationListener
            )
            providerStarted = true
        }
        if (
            locationManager.isProviderEnabled(
                LocationManager.NETWORK_PROVIDER
            )
        ) {
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                LOCATION_UPDATE_INTERVAL_MILLIS,
                LOCATION_UPDATE_DISTANCE_METERS,
                locationListener
            )
            providerStarted = true
        }
        if (!providerStarted) {
            Log.w(TAG, "GPS和网络定位当前均不可用")
        }

        val lastKnownLocation = if (fineGranted) {
            locationManager.getLastKnownLocation(
                LocationManager.GPS_PROVIDER
            ) ?: locationManager.getLastKnownLocation(
                LocationManager.NETWORK_PROVIDER
            )
        } else {
            locationManager.getLastKnownLocation(
                LocationManager.NETWORK_PROVIDER
            )
        }
        lastKnownLocation?.let(::updateLocation)
        return true
    }

    // 更新最近位置、通知文本并按节流规则请求 POI。
    private fun updateLocation(location: Location) {
        val previousLocation = latestLocation
        if (
            previousLocation == null ||
            location.distanceTo(previousLocation) >=
            POI_SEARCH_DISTANCE_METERS
        ) {
            mainHandler.removeCallbacks(continuousScanRunnable)
            continuousScanScheduled = false
        }
        latestLocation = Location(location)
        val speedKmh = if (location.hasSpeed()) {
            location.speed * METERS_PER_SECOND_TO_KILOMETERS_PER_HOUR
        } else {
            0F
        }
        latestMovementClass = movementPolicy.classify(
            speedKmh = speedKmh,
            distanceMeters = previousLocation?.let(location::distanceTo),
            elapsedMillis = previousLocation?.let { previous ->
                (location.time - previous.time).coerceAtLeast(0L)
            }
        )
        providerCoordinator.updateLocation(
            LocationSnapshot(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyMeters = location.accuracy,
                speedKmh = speedKmh,
                timestampMillis = location.time,
                movementClass = latestMovementClass
            )
        )
        val locationText = String.format(
            Locale.US,
            "纬度 %.6f，经度 %.6f，精度 %.1f 米，速度 %.2f km/h，状态 %s",
            location.latitude,
            location.longitude,
            location.accuracy,
            speedKmh,
            latestMovementClass.name
        )
        Log.d(TAG, locationText)
        onStatusChanged(locationText)
        requestNearbyPoiSearchIfNeeded(location)
        scheduleContinuousScan()
    }

    // 按鉴权、精度、时间和移动距离节流 POI 搜索。
    private fun requestNearbyPoiSearchIfNeeded(
        location: Location,
        forceSearch: Boolean = false
    ): Boolean {
        if (!::nearbyPoiSearcher.isInitialized) {
            return false
        }
        if (
            !BaiduMapSdkManager.isAuthCompleted() &&
            !BaiduMapSdkManager.canTryRequestWithoutAuthResult()
        ) {
            return false
        }
        if (
            BaiduMapSdkManager.isAuthCompleted() &&
            !BaiduMapSdkManager.isAuthPassed()
        ) {
            onStatusChanged("百度地图鉴权失败，请查看Logcat")
            return false
        }
        if (
            isPoiSearchRunning ||
            location.accuracy >= MAX_ACCEPTABLE_ACCURACY_METERS
        ) {
            return false
        }

        val nowMillis = System.currentTimeMillis()
        val elapsedMillis = nowMillis - lastPoiSearchTimeMillis
        val movedMeters = lastPoiSearchLocation?.let { previous ->
            location.distanceTo(previous)
        } ?: Float.MAX_VALUE
        val shouldSearch = forceSearch ||
                lastPoiSearchLocation == null ||
                elapsedMillis >= POI_SEARCH_INTERVAL_MILLIS ||
                movedMeters >= POI_SEARCH_DISTANCE_METERS
        if (!shouldSearch) {
            return false
        }

        isPoiSearchRunning = true
        lastPoiSearchTimeMillis = nowMillis
        lastPoiSearchLocation = Location(location)
        val started = nearbyPoiSearcher.searchNearby(
            latitude = location.latitude,
            longitude = location.longitude,
            radiusMeters = POI_SEARCH_RADIUS_METERS
        )
        if (!started) {
            isPoiSearchRunning = false
        }
        return started
    }

    // 判断应用是否具有指定定位权限。
    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val TAG = "CruiseDiscovery"
        const val LOCATION_UPDATE_INTERVAL_MILLIS = 1_000L
        const val LOCATION_UPDATE_DISTANCE_METERS = 10F
        const val POI_SEARCH_RADIUS_METERS = 1_000
        const val POI_SEARCH_INTERVAL_MILLIS = 30_000L
        const val POI_SEARCH_DISTANCE_METERS = 50F
        const val MAX_ACCEPTABLE_ACCURACY_METERS = 500F
        const val METERS_PER_SECOND_TO_KILOMETERS_PER_HOUR = 3.6F
        const val CONTINUOUS_SCAN_RETRY_DELAY_MILLIS = 1_000L
    }
}
