// @Time: 2026/06/29 02:25
// @Author: MrWu
// @File: CruiseService.kt
// @Software: Android Studio


@file:Suppress("SpellCheckingInspection")


package com.wu.nearbycheckin


import java.util.Locale

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager


import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner




// 负责前台巡航定位、附近 POI 搜索和悬浮窗展示。
class CruiseService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: ComposeView? = null

    private var customLifecycleOwner: MyLifecycleOwner? = null

    private val overlayPreferences by lazy {
        getSharedPreferences(
            OVERLAY_PREFERENCES_NAME,
            MODE_PRIVATE
        )
    }

    private var overlayVisible = true

    private var currentNotificationText =
        DEFAULT_NOTIFICATION_TEXT

    private lateinit var locationManager: LocationManager

    private lateinit var nearbyPoiSearcher: NearbyPoiSearcher

    private var isPoiSearchRunning = false
    private var lastPoiSearchTimeMillis = 0L
    private var lastPoiSearchLocation: Location? = null

    private lateinit var checkInRepository: CheckInRepository

    private var latestLocation: Location? = null

    private var candidatePoiKey: String? = null

    private var candidatePoiFirstSeenTimeMillis = 0L

    private var checkInVerificationScheduled = false
    private var continuousScanScheduled = false

    private val mainHandler = Handler(
        Looper.getMainLooper()
    )

    /**
     * 百度地图鉴权完成后主动触发一次最近位置的POI检索。
     */
    private val baiduAuthStateListener: (Boolean, String) -> Unit = { passed, description ->
        mainHandler.post {
            if (!passed) {
                Log.e(
                    TAG,
                    "百度地图鉴权失败：$description"
                )

                updateForegroundNotification(
                    "百度地图鉴权失败，请检查AK配置"
                )
                return@post
            }

            Log.d(
                TAG,
                "百度地图鉴权已通过，恢复POI检索：$description"
            )

            val currentLocation = latestLocation

            if (
                currentLocation != null &&
                ::nearbyPoiSearcher.isInitialized
            ) {
                val requestStarted = requestNearbyPoiSearchIfNeeded(
                    location = currentLocation,
                    forceSearch = true
                )

                if (!requestStarted) {
                    scheduleContinuousScan(
                        CONTINUOUS_SCAN_RETRY_DELAY_MILLIS
                    )
                }
            }
        }
    }

    /**
     * 定时重新检索附近地点，确认用户是否仍然停留在同一个地点附近。
     */
    private val checkInVerificationRunnable = Runnable {
        checkInVerificationScheduled = false

        val currentLocation = latestLocation

        if (
            candidatePoiKey == null ||
            currentLocation == null ||
            !::nearbyPoiSearcher.isInitialized
        ) {
            return@Runnable
        }

        if (isPoiSearchRunning) {
            scheduleCheckInVerification()
            return@Runnable
        }

        Log.d(
            TAG,
            "重新检索附近地点，确认是否持续停留"
        )

        isPoiSearchRunning = true
        lastPoiSearchTimeMillis = System.currentTimeMillis()
        lastPoiSearchLocation = Location(currentLocation)

        val requestStarted = nearbyPoiSearcher.searchNearby(
            latitude = currentLocation.latitude,
            longitude = currentLocation.longitude,
            radiusMeters = POI_SEARCH_RADIUS_METERS
        )

        if (!requestStarted) {
            isPoiSearchRunning = false
            scheduleCheckInVerification()
        }
    }

    /**
     * 静止状态下定时重新检索附近地点，确保没有定位回调时也能继续推进候选队列。
     */
    private val continuousScanRunnable = Runnable {
        continuousScanScheduled = false

        val currentLocation = latestLocation

        if (
            currentLocation == null ||
            !::nearbyPoiSearcher.isInitialized
        ) {
            scheduleContinuousScan()
            return@Runnable
        }

        if (isPoiSearchRunning) {
            scheduleContinuousScan()
            return@Runnable
        }

        val requestStarted = requestNearbyPoiSearchIfNeeded(
            location = currentLocation,
            forceSearch = true
        )

        if (!requestStarted) {
            scheduleContinuousScan()
        }
    }
    private val locationListener = object : LocationListener {

        // 接收到新的真实位置时更新日志和通知
        override fun onLocationChanged(location: Location) {
            updateLocationInformation(location)
        }

        // 定位提供器被启用时记录状态
        override fun onProviderEnabled(provider: String) {
            Log.d(TAG, "定位提供器已启用：$provider")
        }

        // 定位提供器被关闭时记录状态
        override fun onProviderDisabled(provider: String) {
            Log.w(TAG, "定位提供器已关闭：$provider")
        }

        // 兼容旧版Android的定位状态回调
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

    // 服务创建时初始化系统服务和悬浮窗加载
    override fun onCreate() {
        super.onCreate()
        BaiduMapSdkManager.addAuthStateListener(
            baiduAuthStateListener
        )

        val baiduSdkInitialized =
            BaiduMapSdkManager.initializeAfterPrivacyConsent(
                applicationContext
            )

        if (!baiduSdkInitialized) {
            BaiduMapSdkManager.removeAuthStateListener(
                baiduAuthStateListener
            )

            Log.e(
                TAG,
                "百度地图SDK初始化失败，停止巡航服务"
            )

            stopSelf()
            return
        }

        windowManager = getSystemService(WindowManager::class.java)

        overlayVisible = overlayPreferences.getBoolean(
            OVERLAY_VISIBLE_KEY,
            true
        )

        startForegroundServiceNotification()

        if (overlayVisible) {
            showFloatingWindow()
        }

        checkInRepository = CheckInRepository(
            applicationContext
        )
        initializeNearbyPoiSearcher()
        startLocationTracking()
    }

    /**
     * 接收服务启动请求，并处理通知栏中的雷达显示切换操作。
     *
     * @param intent 本次启动服务携带的操作信息。
     * @param flags 系统传递的服务启动标记。
     * @param startId 本次服务启动请求编号。
     * @return 返回START_STICKY，服务被回收后允许系统重新创建。
     */
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        if (intent?.action == ACTION_TOGGLE_OVERLAY) {
            toggleFloatingWindow()
        }

        return START_STICKY
    }

    /**
     * 创建通知渠道并启动前台服务通知。
     */
    private fun startForegroundServiceNotification() {
        val notificationManager =
            getSystemService(NotificationManager::class.java)

        val notificationChannel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "巡航服务",
            NotificationManager.IMPORTANCE_LOW
        )

        notificationManager.createNotificationChannel(
            notificationChannel
        )

        currentNotificationText = DEFAULT_NOTIFICATION_TEXT

        startForeground(
            NOTIFICATION_ID,
            buildForegroundNotification(
                currentNotificationText
            )
        )
    }

    /**
     * 创建带有雷达显示切换按钮的前台服务通知。
     *
     * @param contentText 通知栏中显示的定位或打卡状态。
     * @return 已配置完成的前台服务通知。
     */
    private fun buildForegroundNotification(
        contentText: String
    ): Notification {
        val toggleOverlayIntent = Intent(
            this,
            CruiseService::class.java
        ).apply {
            action = ACTION_TOGGLE_OVERLAY
        }

        val toggleOverlayPendingIntent = PendingIntent.getService(
            this,
            TOGGLE_OVERLAY_REQUEST_CODE,
            toggleOverlayIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
        )

        val actionText = if (overlayVisible) {
            "隐藏雷达"
        } else {
            "显示雷达"
        }

        return NotificationCompat.Builder(
            this,
            NOTIFICATION_CHANNEL_ID
        )
            .setContentTitle("微光雷达运行中")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_view,
                actionText,
                toggleOverlayPendingIntent
            )
            .build()
    }

    // 检查权限并开始监听GPS和网络定位
    @SuppressLint("MissingPermission")

    /**
     * 初始化百度地图周边地点检索器，并处理地点识别和自动打卡逻辑。
     */
    private fun initializeNearbyPoiSearcher() {
        nearbyPoiSearcher = NearbyPoiSearcher(
            onSearchSuccess = { nearbyPoiList: List<NearbyPoi> ->
                isPoiSearchRunning = false

                val selectedPoi = selectCheckInCandidate(
                    nearbyPoiList
                )

                if (selectedPoi == null) {
                    Log.d(
                        TAG,
                        "当前位置附近没有未完成的可用地点"
                    )

                    resetCheckInCandidate(
                        "附近没有未完成的可用地点"
                    )

                    updateForegroundNotification(
                        "附近暂未发现未打卡地点"
                    )
                } else {
                    val poiText =
                        "${selectedPoi.name}，距离${selectedPoi.distanceMeters}米"

                    Log.d(
                        TAG,
                        "当前地点：$poiText，分类：${selectedPoi.category}"
                    )

                    updateForegroundNotification(
                        "最近地点：$poiText"
                    )

                    handleCheckInCandidate(
                        selectedPoi
                    )
                }

                scheduleContinuousScan()
            },
            onSearchFailure = { errorMessage: String ->
                isPoiSearchRunning = false

                Log.e(
                    TAG,
                    errorMessage
                )

                updateForegroundNotification(
                    "地点检索失败，请稍后重试"
                )

                if (candidatePoiKey != null) {
                    scheduleCheckInVerification()
                }

                scheduleContinuousScan()
            }
        )
    }

    /**
     * 优先选择当前正在确认停留的地点，避免附近多个地点因距离波动频繁切换。
     *
     * @param nearbyPoiList 本次百度地图返回的周边地点列表。
     * @return 当前最适合作为打卡候选的地点；没有结果时返回null。
     */
    private fun selectCheckInCandidate(
        nearbyPoiList: List<NearbyPoi>
    ): NearbyPoi? {
        if (nearbyPoiList.isEmpty()) {
            return null
        }

        val inRangePoiList = nearbyPoiList.filter { nearbyPoi ->
            nearbyPoi.distanceMeters <= CHECK_IN_MAX_DISTANCE_METERS
        }

        val availablePoiList = inRangePoiList.filter { nearbyPoi ->
            !checkInRepository.hasCompletedRecord(
                buildPoiKey(nearbyPoi)
            )
        }

        Log.d(
            TAG,
            "候选筛选：返回${nearbyPoiList.size}个，" +
                    "范围内${inRangePoiList.size}个，" +
                    "已完成${inRangePoiList.size - availablePoiList.size}个，" +
                    "可用${availablePoiList.size}个"
        )

        if (availablePoiList.isEmpty()) {
            return null
        }

        val currentCandidateKey = candidatePoiKey
        if (currentCandidateKey != null) {
            val existingCandidate = availablePoiList.firstOrNull { nearbyPoi ->
                buildPoiKey(nearbyPoi) == currentCandidateKey
            }

            if (existingCandidate != null) {
                return existingCandidate
            }
        }

        return availablePoiList.firstOrNull()
    }

    /**
     * 判断候选地点的距离、停留时间和重复记录，并在满足条件后保存打卡记录。
     *
     * @param nearbyPoi 当前识别到的打卡候选地点。
     */
    private fun handleCheckInCandidate(
        nearbyPoi: NearbyPoi
    ) {
        val currentTimeMillis = System.currentTimeMillis()
        val poiKey = buildPoiKey(nearbyPoi)
        val requiredStayMillis = getRequiredStayMillis()
        val requiredStaySeconds =
            requiredStayMillis / MILLIS_PER_SECOND

        if (nearbyPoi.distanceMeters > CHECK_IN_MAX_DISTANCE_METERS) {
            resetCheckInCandidate(
                "地点距离超过${CHECK_IN_MAX_DISTANCE_METERS}米"
            )
            scheduleContinuousScan(
                CONTINUOUS_SCAN_RETRY_DELAY_MILLIS
            )
            return
        }

        if (checkInRepository.hasCompletedRecord(poiKey)) {
            Log.d(
                TAG,
                "地点${nearbyPoi.name}已经完成过打卡，永久跳过"
            )

            updateForegroundNotification(
                "已打卡：${nearbyPoi.name}，不再重复"
            )

            resetCheckInCandidate(
                "地点已有历史打卡记录"
            )
            scheduleContinuousScan(
                CONTINUOUS_SCAN_RETRY_DELAY_MILLIS
            )
            return
        }

        if (candidatePoiKey != poiKey) {
            candidatePoiKey = poiKey
            candidatePoiFirstSeenTimeMillis = currentTimeMillis

            Log.d(
                TAG,
                "开始停留确认：${nearbyPoi.name}，" +
                        "距离${nearbyPoi.distanceMeters}米"
            )

            updateForegroundNotification(
                "停留确认：${nearbyPoi.name}，0/${requiredStaySeconds}秒"
            )

            scheduleCheckInVerification()
            return
        }

        val stayDurationMillis =
            currentTimeMillis - candidatePoiFirstSeenTimeMillis

        if (stayDurationMillis < requiredStayMillis) {
            val stayedSeconds =
                (stayDurationMillis / MILLIS_PER_SECOND)
                    .coerceAtLeast(0L)

            Log.d(
                TAG,
                "持续停留：${nearbyPoi.name}，" +
                        "${stayedSeconds}/${requiredStaySeconds}秒"
            )

            updateForegroundNotification(
                "停留确认：${nearbyPoi.name}，" +
                        "${stayedSeconds}/${requiredStaySeconds}秒"
            )

            scheduleCheckInVerification()
            return
        }

        val poiForRecord = nearbyPoi.copy(
            uid = poiKey
        )

        val savedRecord = checkInRepository.createAndSaveRecord(
            poi = poiForRecord,
            stayDurationMillis = stayDurationMillis,
            checkInTimeMillis = currentTimeMillis
        )

        if (savedRecord != null) {
            Log.d(
                TAG,
                "自动打卡成功：${savedRecord.poiName}，" +
                        "停留${stayDurationMillis / MILLIS_PER_SECOND}秒"
            )

            updateForegroundNotification(
                "本地打卡成功：${savedRecord.poiName}"
            )

            resetCheckInCandidate(
                "本次打卡已经完成"
            )
            scheduleContinuousScan(
                CONTINUOUS_SCAN_RETRY_DELAY_MILLIS
            )
        } else {
            Log.e(
                TAG,
                "自动打卡记录保存失败：${nearbyPoi.name}"
            )

            updateForegroundNotification(
                "打卡保存失败，请稍后重试"
            )

            scheduleCheckInVerification()
        }
    }

    /**
     * 根据当前定位速度返回本次打卡需要的停留确认时长。
     */
    private fun getRequiredStayMillis(): Long {
        if (isFastCheckInMode()) {
            return FAST_CHECK_IN_REQUIRED_STAY_MILLIS
        }

        return CHECK_IN_REQUIRED_STAY_MILLIS
    }

    /**
     * 获取地点稳定标识；优先使用百度POI UID，UID为空时使用名称和坐标组合。
     *
     * @param nearbyPoi 需要生成标识的地点。
     * @return 可用于重复判断和候选地点比较的唯一字符串。
     */
    private fun buildPoiKey(
        nearbyPoi: NearbyPoi
    ): String {
        if (nearbyPoi.uid.isNotBlank()) {
            return nearbyPoi.uid
        }

        return String.format(
            Locale.US,
            "%s|%.5f|%.5f",
            nearbyPoi.name,
            nearbyPoi.latitude,
            nearbyPoi.longitude
        )
    }

    /**
     * 安排下一次地点检索，用于持续确认用户仍停留在同一地点。
     */
    private fun scheduleCheckInVerification() {
        if (
            candidatePoiKey == null ||
            checkInVerificationScheduled
        ) {
            return
        }

        checkInVerificationScheduled = true

        mainHandler.postDelayed(
            checkInVerificationRunnable,
            getCheckInVerificationIntervalMillis()
        )
    }

    /**
     * 根据当前速度返回下一次停留确认的调度间隔。
     */
    private fun getCheckInVerificationIntervalMillis(): Long {
        return if (isFastCheckInMode()) {
            FAST_CHECK_IN_VERIFICATION_INTERVAL_MILLIS
        } else {
            CHECK_IN_VERIFICATION_INTERVAL_MILLIS
        }
    }

    /**
     * 判断当前定位速度是否达到高速打卡模式。
     */
    private fun isFastCheckInMode(): Boolean {
        val currentLocation = latestLocation

        if (
            currentLocation == null ||
            !currentLocation.hasSpeed()
        ) {
            return false
        }

        val speedKmh =
            currentLocation.speed *
                    METERS_PER_SECOND_TO_KILOMETERS_PER_HOUR

        return speedKmh > FAST_CHECK_IN_SPEED_THRESHOLD_KMH
    }

    /**
     * 安排下一次静止心跳检索，避免手机不移动时扫描流程停住。
     *
     * @param delayMillis 下一次心跳检索的延迟时间，单位为毫秒。
     */
    private fun scheduleContinuousScan(
        delayMillis: Long = getContinuousScanIntervalMillis()
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

    /**
     * 根据当前速度返回连续扫描心跳间隔。
     */
    private fun getContinuousScanIntervalMillis(): Long {
        return if (isFastCheckInMode()) {
            FAST_CHECK_IN_VERIFICATION_INTERVAL_MILLIS
        } else {
            CONTINUOUS_SCAN_HEARTBEAT_INTERVAL_MILLIS
        }
    }

    /**
     * 清除当前候选地点和停留计时状态。
     *
     * @param reason 本次重置的原因，用于调试日志。
     */
    private fun resetCheckInCandidate(
        reason: String
    ) {
        if (candidatePoiKey != null) {
            Log.d(
                TAG,
                "重置打卡候选地点：$reason"
            )
        }

        mainHandler.removeCallbacks(
            checkInVerificationRunnable
        )

        candidatePoiKey = null
        candidatePoiFirstSeenTimeMillis = 0L
        checkInVerificationScheduled = false
    }

    // 检查定位权限后启动系统位置更新监听。
    private fun startLocationTracking() {
        val fineLocationGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseLocationGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fineLocationGranted && !coarseLocationGranted) {
            Log.e(TAG, "定位权限尚未授予，巡航服务停止")
            stopSelf()
            return
        }

        locationManager = getSystemService(LocationManager::class.java)

        var locationProviderStarted = false

        if (
            fineLocationGranted &&
            locationManager.isProviderEnabled(
                LocationManager.GPS_PROVIDER
            )
        ) {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                LOCATION_UPDATE_INTERVAL_MS,
                LOCATION_UPDATE_DISTANCE_METERS,
                locationListener
            )

            locationProviderStarted = true
            Log.d(TAG, "GPS定位监听已启动")
        }

        if (
            locationManager.isProviderEnabled(
                LocationManager.NETWORK_PROVIDER
            )
        ) {
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                LOCATION_UPDATE_INTERVAL_MS,
                LOCATION_UPDATE_DISTANCE_METERS,
                locationListener
            )

            locationProviderStarted = true
            Log.d(TAG, "网络定位监听已启动")
        }

        if (!locationProviderStarted) {
            Log.w(TAG, "GPS和网络定位当前均不可用")
        }

        val lastKnownLocation = if (fineLocationGranted) {
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

        lastKnownLocation?.let { location ->
            updateLocationInformation(location)
        }
    }

    // 将最新经纬度输出到Logcat并更新前台通知
    /**
     * 处理最新定位结果，更新通知并根据条件发起周边地点检索。
     *
     * @param location Android系统返回的最新真实位置。
     */
    private fun updateLocationInformation(location: Location) {

        latestLocation = Location(location)

        val locationText = String.format(
            Locale.US,
            "纬度 %.6f，经度 %.6f，精度 %.0f 米",
            location.latitude,
            location.longitude,
            location.accuracy
        )

        Log.d(
            TAG,
            locationText
        )

        updateForegroundNotification(locationText)
        val requestStarted = requestNearbyPoiSearchIfNeeded(location)

        if (!requestStarted) {
            scheduleContinuousScan()
        }

        scheduleContinuousScan()
    }

    /**
     * 更新前台服务通知栏中显示的状态文字。
     *
     * @param contentText 需要显示在通知栏中的状态内容。
     */
    private fun updateForegroundNotification(
        contentText: String
    ) {
        currentNotificationText = contentText

        val notificationManager =
            getSystemService(NotificationManager::class.java)

        notificationManager.notify(
            NOTIFICATION_ID,
            buildForegroundNotification(
                contentText
            )
        )
    }

    /**
     * 根据定位精度、移动距离、时间间隔和强制标记，判断是否需要重新检索周边地点。
     *
     * @param location Android系统返回的最新真实位置。
     * @param forceSearch 为true时忽略移动距离和普通检索间隔，用于静止心跳。
     * @return 已成功发起异步POI检索时返回true，否则返回false。
     */
    private fun requestNearbyPoiSearchIfNeeded(
        location: Location,
        forceSearch: Boolean = false
    ): Boolean {
        if (!::nearbyPoiSearcher.isInitialized) {
            Log.w(
                TAG,
                "POI检索器尚未初始化"
            )
            return false
        }

        if (!BaiduMapSdkManager.isAuthCompleted()) {
            if (!BaiduMapSdkManager.canTryRequestWithoutAuthResult()) {
                Log.d(
                    TAG,
                    "百度地图鉴权尚未完成，暂缓POI检索"
                )

                return false
            }

            Log.w(
                TAG,
                "百度地图鉴权回调超时未返回，先尝试发起POI检索"
            )
        }

        if (
            BaiduMapSdkManager.isAuthCompleted() &&
            !BaiduMapSdkManager.isAuthPassed()
        ) {
            val authDescription =
                BaiduMapSdkManager.getAuthDescription()

            Log.e(
                TAG,
                "百度地图鉴权未通过：$authDescription"
            )

            updateForegroundNotification(
                "百度地图鉴权失败，请查看Logcat"
            )

            return false
        }

        if (isPoiSearchRunning) {
            return false
        }

        if (location.accuracy > MAX_ACCEPTABLE_ACCURACY_METERS) {
            Log.d(
                TAG,
                "当前定位精度较低，暂不检索POI：${location.accuracy}米"
            )
            return false
        }

        val currentTimeMillis = System.currentTimeMillis()
        val previousLocation = lastPoiSearchLocation

        val elapsedTimeMillis =
            currentTimeMillis - lastPoiSearchTimeMillis

        val movedDistanceMeters = previousLocation?.let {
            location.distanceTo(it)
        } ?: Float.MAX_VALUE

        val shouldSearch =
            forceSearch ||
                    previousLocation == null ||
                    elapsedTimeMillis >= POI_SEARCH_INTERVAL_MILLIS ||
                    movedDistanceMeters >= POI_SEARCH_DISTANCE_METERS

        if (!shouldSearch) {
            return false
        }

        isPoiSearchRunning = true
        lastPoiSearchTimeMillis = currentTimeMillis
        lastPoiSearchLocation = Location(location)

        Log.d(
            TAG,
            "开始检索当前位置${POI_SEARCH_RADIUS_METERS}米范围内的地点"
        )

        val requestStarted = nearbyPoiSearcher.searchNearby(
            latitude = location.latitude,
            longitude = location.longitude,
            radiusMeters = POI_SEARCH_RADIUS_METERS
        )

        if (!requestStarted) {
            isPoiSearchRunning = false
        }

        return requestStarted
    }

    /**
     * 创建微光雷达悬浮窗，并恢复用户上次保存的位置。
     */
    private fun showFloatingWindow() {
        if (floatingView != null) {
            return
        }

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START

            x = overlayPreferences.getInt(
                OVERLAY_POSITION_X_KEY,
                DEFAULT_OVERLAY_POSITION_X
            )

            y = overlayPreferences.getInt(
                OVERLAY_POSITION_Y_KEY,
                DEFAULT_OVERLAY_POSITION_Y
            )
        }

        customLifecycleOwner = MyLifecycleOwner().apply {
            start()
        }

        val radarView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(
                customLifecycleOwner
            )

            setViewTreeViewModelStoreOwner(
                customLifecycleOwner
            )

            setViewTreeSavedStateRegistryOwner(
                customLifecycleOwner
            )

            setContent {
                GlowRadarOverlay(
                    onClose = {
                        hideFloatingWindowAndPersistChoice()
                    }
                )
            }
        }

        installFloatingWindowDragListener(
            radarView,
            layoutParams
        )

        windowManager.addView(
            radarView,
            layoutParams
        )

        floatingView = radarView

        Log.d(
            TAG,
            "雷达悬浮窗已经显示"
        )
    }

    /**
     * 为雷达悬浮窗安装拖动监听，并在拖动结束后保存当前位置。
     *
     * @param radarView 当前雷达悬浮窗视图。
     * @param layoutParams 当前悬浮窗布局参数。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun installFloatingWindowDragListener(
        radarView: ComposeView,
        layoutParams: WindowManager.LayoutParams
    ) {
        var initialWindowX = 0
        var initialWindowY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var hasMoved = false

        radarView.isClickable = true

        radarView.setOnTouchListener { touchedView, motionEvent ->
            when (motionEvent.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialWindowX = layoutParams.x
                    initialWindowY = layoutParams.y
                    initialTouchX = motionEvent.rawX
                    initialTouchY = motionEvent.rawY
                    hasMoved = false

                    Log.d(
                        TAG,
                        "开始拖动雷达：x=$initialWindowX，y=$initialWindowY"
                    )

                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val movedX =
                        (motionEvent.rawX - initialTouchX).toInt()

                    val movedY =
                        (motionEvent.rawY - initialTouchY).toInt()

                    if (
                        kotlin.math.abs(movedX) > DRAG_THRESHOLD_PIXELS ||
                        kotlin.math.abs(movedY) > DRAG_THRESHOLD_PIXELS
                    ) {
                        hasMoved = true
                    }

                    val maximumX = (
                            resources.displayMetrics.widthPixels -
                                    getFloatingWindowMeasuredWidth(
                                        touchedView
                                    )
                            ).coerceAtLeast(0)

                    val maximumY = (
                            resources.displayMetrics.heightPixels -
                                    getFloatingWindowMeasuredHeight(
                                        touchedView
                                    )
                            ).coerceAtLeast(0)

                    layoutParams.x = (
                            initialWindowX + movedX
                            ).coerceIn(
                            0,
                            maximumX
                        )

                    layoutParams.y = (
                            initialWindowY + movedY
                            ).coerceIn(
                            0,
                            maximumY
                        )

                    windowManager.updateViewLayout(
                        touchedView,
                        layoutParams
                    )

                    true
                }

                MotionEvent.ACTION_UP -> {
                    saveFloatingWindowPosition(
                        layoutParams.x,
                        layoutParams.y
                    )

                    Log.d(
                        TAG,
                        "雷达拖动结束：x=${layoutParams.x}，y=${layoutParams.y}"
                    )

                    if (!hasMoved) {
                        touchedView.performClick()
                    }

                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    saveFloatingWindowPosition(
                        layoutParams.x,
                        layoutParams.y
                    )

                    true
                }

                else -> false
            }
        }
    }

    /**
     * 获取当前悬浮窗宽度，用于拖动边界计算。
     *
     * @param floatingWindowView 当前悬浮窗视图。
     * @return 可用于屏幕边界计算的像素宽度。
     */
    private fun getFloatingWindowMeasuredWidth(
        floatingWindowView: android.view.View
    ): Int {
        return floatingWindowView.width.takeIf { width ->
            width > 0
        } ?: (
                OVERLAY_FALLBACK_SIZE_DP *
                        resources.displayMetrics.density
                ).toInt()
    }

    /**
     * 获取当前悬浮窗高度，用于拖动边界计算。
     *
     * @param floatingWindowView 当前悬浮窗视图。
     * @return 可用于屏幕边界计算的像素高度。
     */
    private fun getFloatingWindowMeasuredHeight(
        floatingWindowView: android.view.View
    ): Int {
        return floatingWindowView.height.takeIf { height ->
            height > 0
        } ?: (
                OVERLAY_FALLBACK_SIZE_DP *
                        resources.displayMetrics.density
                ).toInt()
    }

    /**
     * 保存雷达悬浮窗当前位置，供下次启动时恢复。
     *
     * @param positionX 悬浮窗距离屏幕左侧的像素位置。
     * @param positionY 悬浮窗距离屏幕顶部的像素位置。
     */
    private fun saveFloatingWindowPosition(
        positionX: Int,
        positionY: Int
    ) {
        overlayPreferences.edit {
            putInt(
                OVERLAY_POSITION_X_KEY,
                positionX
            )

            putInt(
                OVERLAY_POSITION_Y_KEY,
                positionY
            )
        }
    }
    /**
     * 从屏幕移除雷达悬浮窗，但保持定位和自动打卡服务运行。
     */
    private fun hideFloatingWindow() {
        val radarView = floatingView
            ?: return

        windowManager.removeView(
            radarView
        )

        floatingView = null

        customLifecycleOwner?.stop()
        customLifecycleOwner = null

        Log.d(
            TAG,
            "雷达悬浮窗已经隐藏，后台服务继续运行"
        )
    }

    /**
     * 从悬浮窗内部隐藏雷达，并保存用户隐藏选择。
     */
    private fun hideFloatingWindowAndPersistChoice() {
        overlayVisible = false

        overlayPreferences.edit {
            putBoolean(
                OVERLAY_VISIBLE_KEY,
                false
            )
        }

        hideFloatingWindow()

        updateForegroundNotification(
            currentNotificationText
        )
    }

    /**
     * 在显示与隐藏雷达悬浮窗之间切换，并保存用户选择。
     */
    private fun toggleFloatingWindow() {
        overlayVisible = !overlayVisible

        overlayPreferences.edit {
            putBoolean(
                OVERLAY_VISIBLE_KEY,
                overlayVisible
            )
        }

        if (overlayVisible) {
            showFloatingWindow()
        } else {
            hideFloatingWindow()
        }

        updateForegroundNotification(
            currentNotificationText
        )
    }

    /**
     * 服务被系统销毁时停止定位、释放POI检索器并清理悬浮窗。
     */
    override fun onDestroy() {

        BaiduMapSdkManager.removeAuthStateListener(
            baiduAuthStateListener
        )

        mainHandler.removeCallbacks(
            checkInVerificationRunnable
        )
        mainHandler.removeCallbacks(
            continuousScanRunnable
        )

        latestLocation = null
        candidatePoiKey = null
        candidatePoiFirstSeenTimeMillis = 0L
        checkInVerificationScheduled = false
        continuousScanScheduled = false

        if (::locationManager.isInitialized) {
            locationManager.removeUpdates(locationListener)
        }

        if (::nearbyPoiSearcher.isInitialized) {
            nearbyPoiSearcher.destroy()
        }

        if (floatingView != null) {
            hideFloatingWindow()
        } else {
            customLifecycleOwner?.stop()
            customLifecycleOwner = null
        }
        super.onDestroy()
    }
    // 绑定服务的内部接口存根
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private companion object {
        const val TAG = "CruiseService"
        const val NOTIFICATION_CHANNEL_ID = "cruise_service_channel"
        const val NOTIFICATION_ID = 101
        const val LOCATION_UPDATE_INTERVAL_MS = 5_000L
        const val LOCATION_UPDATE_DISTANCE_METERS = 10f

        const val POI_SEARCH_RADIUS_METERS = 1_000
        const val POI_SEARCH_INTERVAL_MILLIS = 30_000L
        const val POI_SEARCH_DISTANCE_METERS = 50f
        const val MAX_ACCEPTABLE_ACCURACY_METERS = 100f


        const val CHECK_IN_MAX_DISTANCE_METERS = 1_000
        const val CHECK_IN_REQUIRED_STAY_MILLIS = 5_000L
        const val CHECK_IN_VERIFICATION_INTERVAL_MILLIS = 5_000L
        const val FAST_CHECK_IN_SPEED_THRESHOLD_KMH = 60f
        const val FAST_CHECK_IN_REQUIRED_STAY_MILLIS = 1_000L
        const val FAST_CHECK_IN_VERIFICATION_INTERVAL_MILLIS = 1_000L
        const val METERS_PER_SECOND_TO_KILOMETERS_PER_HOUR = 3.6f
        const val CONTINUOUS_SCAN_HEARTBEAT_INTERVAL_MILLIS = 15_000L
        const val CONTINUOUS_SCAN_RETRY_DELAY_MILLIS = 1_000L
        const val MILLIS_PER_SECOND = 1_000L

        const val ACTION_TOGGLE_OVERLAY =
            "com.wu.nearbycheckin.action.TOGGLE_OVERLAY"

        const val OVERLAY_PREFERENCES_NAME =
            "overlay_preferences"

        const val OVERLAY_VISIBLE_KEY =
            "overlay_visible"

        const val OVERLAY_POSITION_X_KEY =
            "overlay_position_x"

        const val OVERLAY_POSITION_Y_KEY =
            "overlay_position_y"

        const val DEFAULT_OVERLAY_POSITION_X = 24
        const val DEFAULT_OVERLAY_POSITION_Y = 180

        const val OVERLAY_FALLBACK_SIZE_DP = 180
        const val DRAG_THRESHOLD_PIXELS = 8

        const val TOGGLE_OVERLAY_REQUEST_CODE = 102

        const val DEFAULT_NOTIFICATION_TEXT =
            "正在为您自动监测周边打卡点..."


    }
}

// 自定义生命周期复合持有类，用于向Service环境中的Compose组件提供合规上下文
class MyLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val controller = SavedStateRegistryController.create(this)

    init {
        controller.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

    // 激活并流转生命周期至就绪状态
    fun start() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    // 停止并彻底销毁规整生命周期与数据存储
    fun stop() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}
