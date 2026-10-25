// @Time: 2026/07/20 18:31
// @Author: MrWu
// @File: MainActivity.kt
// @Software: Android Studio


package com.wu.nearbycheckin.presentation.main


import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

import com.wu.nearbycheckin.app.NearbyCheckInApplication
import com.wu.nearbycheckin.platform.multiwindow.DualMapPaneLaunchPolicy
import com.wu.nearbycheckin.presentation.components.CyberpunkKVBackground
import com.wu.nearbycheckin.presentation.components.GlowRadarOverlay
import com.wu.nearbycheckin.ui.theme.NearbyCheckInTheme


/**
 * 作为 Compose 宿主并处理 ViewModel 发出的系统导航 Effect。
 */
class MainActivity : ComponentActivity() {

    private lateinit var viewModel: MainViewModel
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingBaiduPaneReplacement = false
    private val baiduPaneReplacementRunnable = Runnable {
        if (pendingBaiduPaneReplacement && isInMultiWindowMode) {
            openBaiduInCurrentPane()
        }
    }

    private val locationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val locationGranted =
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ==
                        true ||
                        permissions[
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ] == true
            if (locationGranted) {
                viewModel.onEvent(MainUiEvent.RetryStartCruise)
            } else {
                Toast.makeText(
                    this,
                    "未授予定位权限，巡航自动化未启动",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private val overlayPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            viewModel.onEvent(MainUiEvent.RetryStartCruise)
        }

    // 创建 ViewModel、收集 Effect 并加载纯渲染 Compose 界面。
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingBaiduPaneReplacement = savedInstanceState?.getBoolean(
            PENDING_BAIDU_PANE_REPLACEMENT_KEY
        ) == true
        enableEdgeToEdge()

        val container =
            (application as NearbyCheckInApplication).appContainer
        viewModel = ViewModelProvider(
            this,
            MainViewModelFactory(
                repository = container.transactionRepository,
                startCruiseUseCase = container.startCruiseUseCase,
                stopCruiseUseCase = container.stopCruiseUseCase,
                queryRecordsUseCase = container.queryRecordsUseCase,
                clearRecordsUseCase = container.clearRecordsUseCase,
                coordinator = container.checkInCoordinator,
                providerCoordinator =
                    container.providerAutomationCoordinator
            )
        )[MainViewModel::class.java]
        pendingBaiduPaneReplacement =
            pendingBaiduPaneReplacement ||
                    viewModel.uiState.value
                        .isBaiduPaneReplacementPending
        val installedVersionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull().orEmpty().ifBlank { "unknown" }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.effects.collect { effect ->
                    handleEffect(effect)
                }
            }
        }

        setContent {
            val state by viewModel.uiState.collectAsState()
            NearbyCheckInTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    Box(modifier = Modifier.fillMaxSize()) {
                        CyberpunkKVBackground()
                        MainScreen(
                            state = state,
                            onEvent = viewModel::onEvent,
                            versionName = installedVersionName,
                            modifier = Modifier.padding(innerPadding)
                        )
                        // 暂时停用主界面中的“巡航中”状态组件，保留代码以便后续恢复。
                        // Box(
                        //     modifier = Modifier
                        //         .padding(innerPadding)
                        //         .padding(top = 16.dp, end = 16.dp)
                        //         .align(Alignment.TopEnd)
                        // ) {
                        //     GlowRadarOverlay()
                        // }
                    }
                }
            }
        }

        if (pendingBaiduPaneReplacement && isInMultiWindowMode) {
            scheduleBaiduPaneReplacement(
                BAIDU_REPLACE_AFTER_SPLIT_DELAY_MILLIS
            )
        }
    }

    // 在分屏配置生效后尽快让百度地图接管本应用所在窗格。
    override fun onMultiWindowModeChanged(
        isInMultiWindowMode: Boolean,
        newConfig: Configuration
    ) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        if (pendingBaiduPaneReplacement && isInMultiWindowMode) {
            scheduleBaiduPaneReplacement(
                BAIDU_REPLACE_AFTER_SPLIT_DELAY_MILLIS
            )
        }
    }

    // 保存跨分屏配置重建仍需执行的百度窗格替换状态。
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(
            PENDING_BAIDU_PANE_REPLACEMENT_KEY,
            pendingBaiduPaneReplacement
        )
        super.onSaveInstanceState(outState)
    }

    // 销毁页面时移除只属于当前 Activity 实例的延时任务。
    override fun onDestroy() {
        mainHandler.removeCallbacks(baiduPaneReplacementRunnable)
        super.onDestroy()
    }

    // 将一次性 ViewModel Effect 转换为 Android 系统动作。
    private fun handleEffect(effect: MainUiEffect) {
        when (effect) {
            MainUiEffect.RequestLocationPermission ->
                requestLocationPermission()
            MainUiEffect.RequestOverlayPermission ->
                openOverlayPermissionSettings()
            MainUiEffect.OpenAccessibilitySettings ->
                openAccessibilitySettings()
            MainUiEffect.OpenDualMapSplit ->
                openDualMapSplit()
            is MainUiEffect.OpenMapApplications ->
                openMapApplications(effect.packageNames)
            is MainUiEffect.ShowToast ->
                Toast.makeText(
                    this,
                    effect.message,
                    Toast.LENGTH_LONG
                ).show()
        }
    }

    // 请求定位权限并提示选择可长期保留的“使用中允许”模式。
    private fun requestLocationPermission() {
        Toast.makeText(
            this,
            "请选择“仅在使用中允许”，不要选择“仅限这一次”",
            Toast.LENGTH_LONG
        ).show()
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        )
    }

    // 打开系统悬浮窗授权页面并等待结果。
    private fun openOverlayPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "package:$packageName".toUri()
        )
        try {
            overlayPermissionLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                this,
                "无法打开悬浮窗设置，请手动进入系统设置",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // 打开系统无障碍服务设置页面。
    private fun openAccessibilitySettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            )
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                this,
                "无法打开无障碍设置，请手动进入系统设置",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // 从前台 Activity 依次建立“应用+高德”相邻窗格并用百度替换应用窗格。
    private fun openDualMapSplit() {
        val amapLaunchIntent = packageManager
            .getLaunchIntentForPackage(AMAP_PACKAGE_NAME)
        if (amapLaunchIntent == null) {
            reportMapLaunchStatus("高德地图未安装，无法创建双地图分屏")
            return
        }

        val adjacentAmapIntent = Intent(amapLaunchIntent).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        }
        pendingBaiduPaneReplacement = true
        try {
            Log.i(TAG, "从前台请求高德地图进入相邻窗口")
            startActivity(adjacentAmapIntent)
            reportMapLaunchStatus(
                "高德地图已进入相邻窗口，等待百度地图接管当前窗格"
            )
        } catch (exception: RuntimeException) {
            completeBaiduPaneReplacement()
            Log.w(TAG, "高德地图相邻窗口启动失败", exception)
            reportMapLaunchStatus("高德地图相邻窗口启动失败，请检查系统分屏权限")
            return
        }

        scheduleBaiduPaneReplacement(
            if (isInMultiWindowMode) {
                BAIDU_REPLACE_AFTER_SPLIT_DELAY_MILLIS
            } else {
                BAIDU_REPLACE_FALLBACK_DELAY_MILLIS
            }
        )
    }

    // 安排百度替换任务并覆盖旧实例留下的重复任务。
    private fun scheduleBaiduPaneReplacement(delayMillis: Long) {
        mainHandler.removeCallbacks(baiduPaneReplacementRunnable)
        mainHandler.postDelayed(
            baiduPaneReplacementRunnable,
            delayMillis
        )
    }

    // 在高德相邻窗格创建后用百度普通首页替换应用所在窗格。
    private fun openBaiduInCurrentPane() {
        val baiduLaunchIntent = packageManager
            .getLaunchIntentForPackage(BAIDU_MAP_PACKAGE_NAME)
        if (baiduLaunchIntent == null) {
            completeBaiduPaneReplacement()
            reportMapLaunchStatus("百度地图未安装，双地图分屏未完成")
            return
        }
        val baiduIntent = Intent(baiduLaunchIntent).apply {
            flags = DualMapPaneLaunchPolicy
                .currentPaneReplacementFlags(flags)
        }
        try {
            Log.i(TAG, "请求百度地图替换应用所在窗口")
            startActivity(baiduIntent)
            completeBaiduPaneReplacement()
            reportMapLaunchStatus(
                "已请求百度和高德进入双地图分屏，正在等待窗口确认"
            )
        } catch (exception: RuntimeException) {
            completeBaiduPaneReplacement()
            Log.w(TAG, "百度地图当前窗格启动失败", exception)
            reportMapLaunchStatus("百度地图启动失败，双地图分屏未完成")
        }
    }

    // 清除 Activity 与 ViewModel 中已经完成的百度窗格替换状态。
    private fun completeBaiduPaneReplacement() {
        pendingBaiduPaneReplacement = false
        mainHandler.removeCallbacks(baiduPaneReplacementRunnable)
        viewModel.onEvent(
            MainUiEvent.CompleteBaiduPaneReplacement
        )
    }

    // 将地图启动阶段状态回传给 ViewModel。
    private fun reportMapLaunchStatus(statusText: String) {
        viewModel.onEvent(
            MainUiEvent.MapLaunchReported(statusText)
        )
    }

    // 以前台身份依次打开地图并把结果回传 ViewModel。
    private fun openMapApplications(packageNames: List<String>) {
        val launchedPackages = mutableListOf<String>()
        val failedPackages = mutableListOf<String>()

        packageNames.forEach { targetPackageName ->
            val launchIntent = packageManager
                .getLaunchIntentForPackage(targetPackageName)
            if (launchIntent == null) {
                failedPackages += targetPackageName
                return@forEach
            }

            try {
                startActivity(launchIntent)
                launchedPackages += targetPackageName
            } catch (_: ActivityNotFoundException) {
                failedPackages += targetPackageName
            } catch (_: RuntimeException) {
                failedPackages += targetPackageName
            }
        }

        viewModel.onEvent(
            MainUiEvent.MapLaunchReported(
                buildMapLaunchStatus(
                    requestedPackages = packageNames,
                    launchedPackages = launchedPackages,
                    failedPackages = failedPackages
                )
            )
        )
    }

    // 生成地图链式启动结果的中文状态。
    private fun buildMapLaunchStatus(
        requestedPackages: List<String>,
        launchedPackages: List<String>,
        failedPackages: List<String>
    ): String {
        val requestedNames = requestedPackages.map(::mapDisplayName)
        val launchedNames = launchedPackages.map(::mapDisplayName)
        val failedNames = failedPackages.map(::mapDisplayName)
        return when {
            failedNames.isEmpty() ->
                "已打开${launchedNames.joinToString("和")}，" +
                        "请返回后检查系统链式启动授权"
            launchedNames.isEmpty() ->
                "${requestedNames.joinToString("和")}均打开失败，" +
                        "请检查系统限制"
            else ->
                "已打开${launchedNames.joinToString("和")}，" +
                        "${failedNames.joinToString("和")}打开失败"
        }
    }

    // 将地图包名转换为界面显示名称。
    private fun mapDisplayName(packageName: String): String {
        return when (packageName) {
            BAIDU_MAP_PACKAGE_NAME -> "百度地图"
            AMAP_PACKAGE_NAME -> "高德地图"
            else -> packageName
        }
    }

    private companion object {
        const val TAG = "MainActivity"
        const val BAIDU_MAP_PACKAGE_NAME = "com.baidu.BaiduMap"
        const val AMAP_PACKAGE_NAME = "com.autonavi.minimap"
        const val PENDING_BAIDU_PANE_REPLACEMENT_KEY =
            "pending_baidu_pane_replacement"
        const val BAIDU_REPLACE_AFTER_SPLIT_DELAY_MILLIS = 600L
        const val BAIDU_REPLACE_FALLBACK_DELAY_MILLIS = 2_500L
    }
}
