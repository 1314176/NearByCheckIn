// @Time: 2026/06/29 02:22
// @Author: MrWu
// @File: MainActivity.kt
// @Software: Android Studio


@file:Suppress("SpellCheckingInspection")

package com.wu.nearbycheckin


import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

import kotlin.time.Duration.Companion.milliseconds

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast


import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.delay



import com.wu.nearbycheckin.ui.theme.NearbyCheckInTheme


private const val PRIVACY_PREFERENCES_NAME = "privacy_preferences"
private const val BAIDU_PRIVACY_CONSENT_KEY = "baidu_map_privacy_consent"

private const val BAIDU_MAP_PACKAGE_NAME = "com.baidu.BaiduMap"
private const val AMAP_PACKAGE_NAME = "com.autonavi.minimap"
private const val DIAGNOSTICS_REFRESH_INTERVAL_MILLIS = 1_500L
private const val DIAGNOSTICS_STALE_MILLIS = 45_000L

private val checkInTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss")

/**
 * 保存双地图自动化所需地图应用的安装状态。
 *
 * @property baiduInstalled 百度地图是否已经安装。
 * @property amapInstalled 高德地图是否已经安装。
 */
private data class MapInstallationStatus(
    val baiduInstalled: Boolean,
    val amapInstalled: Boolean
) {

    val allRequiredAppsInstalled: Boolean
        get() = baiduInstalled && amapInstalled
}

/**
 * 检查指定包名的应用是否已经安装。
 *
 * @param context 当前页面或应用上下文。
 * @param packageName 需要检查的应用包名。
 * @return 应用已安装时返回true，否则返回false。
 */
private fun isPackageInstalled(
    context: Context,
    packageName: String
): Boolean {
    return try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(
            packageName,
            0
        )

        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}

/**
 * 检查双地图自动化依赖的百度地图和高德地图的安装状态。
 *
 * @param context 当前页面或应用上下文。
 * @return 当前两款地图应用的安装状态。
 */
private fun checkMapInstallationStatus(
    context: Context
): MapInstallationStatus {
    return MapInstallationStatus(
        baiduInstalled = isPackageInstalled(
            context,
            BAIDU_MAP_PACKAGE_NAME
        ),
        amapInstalled = isPackageInstalled(
            context,
            AMAP_PACKAGE_NAME
        )
    )
}

/**
 * 判断双地图诊断无障碍服务是否已经由用户启用。
 *
 * @param context 当前页面或应用上下文。
 * @return 无障碍服务已启用时返回true，否则返回false。
 */
private fun isDualMapAccessibilityServiceEnabled(
    context: Context
): Boolean {
    val componentName = ComponentName(
        context,
        DualMapAccessibilityService::class.java
    ).flattenToString()

    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ).orEmpty()

    return enabledServices.split(':').any { enabledService ->
        enabledService.equals(
            componentName,
            ignoreCase = true
        )
    }
}

/**
 * 判断指定打卡时间是否属于设备当前时区的今天。
 *
 * @param checkInTimeMillis 打卡时间戳，单位为毫秒。
 * @return 属于今天时返回true，否则返回false。
 */
private fun isTodayCheckIn(
    checkInTimeMillis: Long
): Boolean {
    val checkInDate = Instant.ofEpochMilli(
        checkInTimeMillis
    ).atZone(
        ZoneId.systemDefault()
    ).toLocalDate()

    return checkInDate == LocalDate.now()
}

/**
 * 将打卡时间戳格式化为时分秒文本。
 *
 * @param checkInTimeMillis 打卡时间戳，单位为毫秒。
 * @return 格式为HH:mm:ss的时间文字。
 */
private fun formatCheckInTime(
    checkInTimeMillis: Long
): String {
    return Instant.ofEpochMilli(
        checkInTimeMillis
    ).atZone(
        ZoneId.systemDefault()
    ).format(
        checkInTimeFormatter
        )
}

/**
 * 判断双地图窗口诊断是否仍然有效且两个窗口都已就绪。
 *
 * @param snapshot 最近一次无障碍诊断摘要。
 * @param currentTimeMillis 当前系统时间戳，单位为毫秒。
 * @return 诊断新鲜且百度、高德都已就绪时返回true。
 */
private fun isDualMapDiagnosticsReady(
    snapshot: AccessibilityDiagnosticsSnapshot?,
    currentTimeMillis: Long = System.currentTimeMillis()
): Boolean {
    if (snapshot == null) {
        return false
    }

    val diagnosticsFresh =
        currentTimeMillis - snapshot.lastUpdatedMillis <=
                DIAGNOSTICS_STALE_MILLIS

    return diagnosticsFresh &&
            snapshot.baiduReady &&
            snapshot.amapReady
}

/**
 * 判断双地图窗口诊断是否已经过期。
 *
 * @param snapshot 最近一次无障碍诊断摘要。
 * @param currentTimeMillis 当前系统时间戳，单位为毫秒。
 * @return 没有诊断或诊断已过期时返回true。
 */
private fun isDiagnosticsStale(
    snapshot: AccessibilityDiagnosticsSnapshot?,
    currentTimeMillis: Long = System.currentTimeMillis()
): Boolean {
    return snapshot == null ||
            currentTimeMillis - snapshot.lastUpdatedMillis >
            DIAGNOSTICS_STALE_MILLIS
}

/**
 * 渲染双地图自动化依赖应用的安装状态。
 *
 * @param status 当前百度地图和高德地图的安装状态。
 */
@Composable
private fun MapInstallationStatusText(
    status: MapInstallationStatus
) {
    val baiduStatus = if (status.baiduInstalled) {
        "百度已安装"
    } else {
        "百度未安装"
    }

    val amapStatus = if (status.amapInstalled) {
        "高德已安装"
    } else {
        "高德未安装"
    }

    val statusText = if (status.allRequiredAppsInstalled) {
        "地图安装：$baiduStatus，$amapStatus"
    } else {
        "地图安装：$baiduStatus，$amapStatus；缺失时不能启动双地图自动化"
    }

    Text(
        text = statusText,
        style = MaterialTheme.typography.bodyMedium
    )
}

/**
 * 渲染双地图只读诊断的最近窗口状态。
 *
 * @param snapshot 最近一次无障碍诊断摘要。
 */
@Composable
private fun DiagnosticsStatusText(
    snapshot: AccessibilityDiagnosticsSnapshot?
) {
    if (snapshot == null) {
        Text(
            text = "双地图诊断：尚未收到窗口状态",
            style = MaterialTheme.typography.bodyMedium
        )
        return
    }

    val stale = isDiagnosticsStale(
        snapshot
    )

    val baiduStatus = if (snapshot.baiduReady) {
        "百度已就绪"
    } else {
        "百度未就绪"
    }

    val amapStatus = if (snapshot.amapReady) {
        "高德已就绪"
    } else {
        "高德未就绪"
    }

    val freshnessStatus = if (stale) {
        "诊断已过期"
    } else {
        "诊断有效"
    }

    Text(
        text = "双地图诊断：$baiduStatus，$amapStatus，$freshnessStatus",
        style = MaterialTheme.typography.bodyMedium
    )

    Text(
        text = "窗口${snapshot.totalWindowCount}个，目标${snapshot.targetWindowCount}个，" +
                "节点${snapshot.targetNodeCount}个，" +
                "更新时间${formatCheckInTime(snapshot.lastUpdatedMillis)}",
        style = MaterialTheme.typography.bodySmall
    )

    if (snapshot.baiduReady) {
        Text(
            text = "百度可点节点：${snapshot.baiduClickableNodeCount}个",
            style = MaterialTheme.typography.bodySmall
        )

        if (snapshot.baiduCandidateSummary.isNotBlank()) {
            Text(
                text = "百度候选：${snapshot.baiduCandidateSummary}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    if (snapshot.amapReady) {
        Text(
            text = "高德可点节点：${snapshot.amapClickableNodeCount}个",
            style = MaterialTheme.typography.bodySmall
        )

        if (snapshot.amapCandidateSummary.isNotBlank()) {
            Text(
                text = "高德候选：${snapshot.amapCandidateSummary}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/**
 * 应用主活动，负责初始化主题、主界面以及右上角的微光雷达悬浮组件。
 */
class MainActivity : ComponentActivity() {

    /**
     * Activity 创建时的生命周期入口，负责启用沉浸式边缘布局并加载 Compose 界面。
     *
     * @param savedInstanceState Activity 被系统重新创建时保存的状态数据；首次启动时通常为 null。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NearbyCheckInTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // 使用 Box 容器包裹，让雷达可以像赛博朋克悬浮窗一样飘在主界面上方
                    Box(modifier = Modifier.fillMaxSize()) {
                        NearbyCheckInScreen(
                            modifier = Modifier.padding(innerPadding)
                        )

                        // 把我们亮晶晶的微光雷达悬浮窗完美安放在屏幕右上角
                        Box(
                            modifier = Modifier
                                .padding(innerPadding)
                                .padding(top = 16.dp, end = 16.dp)
                                .align(Alignment.TopEnd)
                        ) {
                            GlowRadarOverlay()
                        }
                    }
                }
            }
        }
    }
}

/**
 * 构建附近打卡自动化主界面，并处理定位权限、通知权限、悬浮窗权限及巡航服务状态。
 *
 * @param modifier 外部传入的 Compose 修饰符，用于控制主界面的尺寸、间距和布局位置。
 */
@SuppressLint("UseKtx")
@Composable
fun NearbyCheckInScreen(modifier: Modifier = Modifier) {
    var isRecording by rememberSaveable {
        mutableStateOf(false)
    }

    val context = LocalContext.current

    val checkInRepository = remember(context) {
        CheckInRepository(
            context.applicationContext
        )
    }

    val diagnosticsRepository = remember(context) {
        AccessibilityDiagnosticsRepository(
            context.applicationContext
        )
    }

    var showRecordsDialog by rememberSaveable {
        mutableStateOf(false)
    }

    var displayedCheckInRecords by remember {
        mutableStateOf<List<CheckInRecord>>(
            emptyList()
        )
    }

    var recordsDialogTitle by rememberSaveable {
        mutableStateOf("本地打卡记录")
    }

    var showClearRecordsDialog by rememberSaveable {
        mutableStateOf(false)
    }

    var diagnosticsSnapshot by remember {
        mutableStateOf<AccessibilityDiagnosticsSnapshot?>(null)
    }

    var autoSplitLaunchStatus by remember {
        mutableStateOf(
            diagnosticsRepository.getAutoSplitLaunchStatus()
        )
    }

    var mapInstallationStatus by remember {
        mutableStateOf(
            checkMapInstallationStatus(
                context
            )
        )
    }

    var autoSplitLaunchRequested by rememberSaveable {
        mutableStateOf(false)
    }

    LaunchedEffect(Unit) {
        if (!autoSplitLaunchRequested) {
            autoSplitLaunchRequested = true
            mapInstallationStatus = checkMapInstallationStatus(
                context
            )

            if (
                mapInstallationStatus.allRequiredAppsInstalled &&
                isDualMapAccessibilityServiceEnabled(context)
            ) {
                diagnosticsRepository.requestAutoSplitLaunch()
                autoSplitLaunchStatus = "软件启动后已请求自动打开双地图分屏"
            } else if (!mapInstallationStatus.allRequiredAppsInstalled) {
                autoSplitLaunchStatus = "百度地图或高德地图未安装，无法自动打开双地图分屏"
            } else {
                autoSplitLaunchStatus = "无障碍服务未启用，无法自动打开双地图分屏"
            }
        }

        while (true) {
            diagnosticsSnapshot = diagnosticsRepository.getLatestSnapshot()
            autoSplitLaunchStatus =
                diagnosticsRepository.getAutoSplitLaunchStatus()
            delay(DIAGNOSTICS_REFRESH_INTERVAL_MILLIS.milliseconds)
        }
    }

    val privacyPreferences = remember(context) {
        context.getSharedPreferences(
            PRIVACY_PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )
    }

    var privacyConsentGranted by rememberSaveable {
        mutableStateOf(
            privacyPreferences.getBoolean(
                BAIDU_PRIVACY_CONSENT_KEY,
                false
            )
        )
    }

    var showPrivacyDialog by rememberSaveable {
        mutableStateOf(false)
    }

    /**
     * 启动巡航前台服务，并将当前页面状态更新为正在运行。
     */
    fun startCruiseService() {
        mapInstallationStatus = checkMapInstallationStatus(
            context
        )

        if (!mapInstallationStatus.allRequiredAppsInstalled) {
            Toast.makeText(
                context,
                "请先安装百度地图和高德地图，再启动双地图自动化",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        diagnosticsSnapshot = diagnosticsRepository.getLatestSnapshot()

        if (!isDualMapDiagnosticsReady(diagnosticsSnapshot)) {
            if (!isDualMapAccessibilityServiceEnabled(context)) {
                Toast.makeText(
                    context,
                    "请先启用 NearbyCheckIn 双地图诊断无障碍服务，系统不允许应用自动开启该权限",
                    Toast.LENGTH_LONG
                ).show()

                try {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_ACCESSIBILITY_SETTINGS
                        )
                    )
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(
                        context,
                        "无法打开无障碍设置，请手动进入系统设置",
                        Toast.LENGTH_LONG
                    ).show()
                }

                return
            }

            diagnosticsRepository.requestAutoSplitLaunch()
            autoSplitLaunchStatus = "已请求自动打开双地图分屏"

            Toast.makeText(
                context,
                "正在自动打开百度地图和高德地图，并尝试进入分屏",
                Toast.LENGTH_LONG
            ).show()
        }

        val serviceIntent = Intent(
            context,
            CruiseService::class.java
        )

        ContextCompat.startForegroundService(
            context,
            serviceIntent
        )

        isRecording = true

    }

    // 接收系统悬浮窗设置页面的返回结果，并在授权成功后启动巡航服务
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(context)) {
            startCruiseService()
        }
    }

    // 检查悬浮窗权限；已授权则直接启动服务，否则跳转到系统授权页面
    val requestOverlayPermissionOrStart: () -> Unit = {
        if (Settings.canDrawOverlays(context)) {
            startCruiseService()
        } else {
            val overlayIntent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:${context.packageName}".toUri()
            )

            overlayPermissionLauncher.launch(overlayIntent)
        }
    }

    // 请求 Android 13 及以上系统的通知权限，授权后继续处理悬浮窗权限
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { notificationGranted ->
        if (notificationGranted || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            requestOverlayPermissionOrStart()
        }
    }

    // 同时请求大致位置和精确位置权限，成功后继续处理通知及悬浮窗权限
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val coarseLocationGranted =
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        val fineLocationGranted =
            permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true

        if (coarseLocationGranted || fineLocationGranted) {
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(
                    Manifest.permission.POST_NOTIFICATIONS
                )
            } else {
                requestOverlayPermissionOrStart()
            }
        }
    }

    // 初始化百度地图SDK，并依次检查定位、通知及悬浮窗权限
    val requestPermissionsAndStart: () -> Unit = {
        val sdkInitialized =
            BaiduMapSdkManager.initializeAfterPrivacyConsent(context)

        if (!sdkInitialized) {
            Toast.makeText(
                context,
                "百度地图服务初始化失败，请检查网络和AK配置",
                Toast.LENGTH_LONG
            ).show()
        } else {
            val coarseLocationGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            val fineLocationGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (coarseLocationGranted || fineLocationGranted) {
                if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(
                        Manifest.permission.POST_NOTIFICATIONS
                    )
                } else {
                    requestOverlayPermissionOrStart()
                }
            } else {
                locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    )
                )
            }
        }
    }

    if (showPrivacyDialog) {
        AlertDialog(
            onDismissRequest = {
                showPrivacyDialog = false
            },
            title = {
                Text(
                    text = "隐私提示"
                )
            },
            text = {
                Text(
                    text = "附近打卡功能需要使用百度地图SDK，" +
                            "并将当前坐标通过网络用于查询周边地点。" +
                            "是否同意并继续使用该功能？"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        privacyPreferences.edit {
                            putBoolean(
                                BAIDU_PRIVACY_CONSENT_KEY,
                                true
                            )
                        }

                        privacyConsentGranted = true
                        showPrivacyDialog = false
                        requestPermissionsAndStart()
                    }
                ) {
                    Text(
                        text = "同意并继续"
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showPrivacyDialog = false
                    }
                ) {
                    Text(
                        text = "暂不同意"
                    )
                }
            }
        )
    }

    if (showRecordsDialog) {
        AlertDialog(
            onDismissRequest = {
                showRecordsDialog = false
            },
            title = {
                Text(
                    text = recordsDialogTitle
                )
            },
            text = {
                if (displayedCheckInRecords.isEmpty()) {
                    Text(
                        text = "暂无本地自动打卡记录。"
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(
                                rememberScrollState()
                            )
                    ) {
                        displayedCheckInRecords.forEachIndexed { index, record ->
                            Text(
                                text = "${index + 1}. ${record.poiName}",
                                style = MaterialTheme.typography.titleMedium
                            )

                            Spacer(
                                modifier = Modifier.height(6.dp)
                            )

                            Text(
                                text = "时间：${
                                    formatCheckInTime(
                                        record.checkInTimeMillis
                                    )
                                }",
                                style = MaterialTheme.typography.bodyMedium
                            )

                            Text(
                                text = "距离：${record.distanceMeters}米",
                                style = MaterialTheme.typography.bodyMedium
                            )

                            Text(
                                text = "停留：${
                                    record.stayDurationMillis / 1_000L
                                }秒",
                                style = MaterialTheme.typography.bodyMedium
                            )

                            if (record.address.isNotBlank()) {
                                Text(
                                    text = "地址：${record.address}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }

                            if (index < displayedCheckInRecords.lastIndex) {
                                Spacer(
                                    modifier = Modifier.height(12.dp)
                                )

                                HorizontalDivider()

                                Spacer(
                                    modifier = Modifier.height(12.dp)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRecordsDialog = false
                    }
                ) {
                    Text(
                        text = "关闭"
                    )
                }
            }
        )
    }

    if (showClearRecordsDialog) {
        AlertDialog(
            onDismissRequest = {
                showClearRecordsDialog = false
            },
            title = {
                Text(
                    text = "清空本地记录"
                )
            },
            text = {
                Text(
                    text = "此操作会清空本地打卡记录和永久去重集合，之后相同地点会重新进入候选队列。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val cleared = checkInRepository.clearAllRecords()

                        Toast.makeText(
                            context,
                            if (cleared) {
                                "本地记录已清空"
                            } else {
                                "本地记录清空失败"
                            },
                            Toast.LENGTH_LONG
                        ).show()

                        showClearRecordsDialog = false
                    }
                ) {
                    Text(
                        text = "确认清空"
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showClearRecordsDialog = false
                    }
                ) {
                    Text(
                        text = "取消"
                    )
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "附近打卡自动化",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = if (isRecording) {
                "状态：正在检索及打卡"
            } else {
                "状态：未开始"
            },
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(modifier = Modifier.height(8.dp))

        MapInstallationStatusText(
            status = mapInstallationStatus
        )

        Spacer(modifier = Modifier.height(8.dp))

        DiagnosticsStatusText(
            snapshot = diagnosticsSnapshot
        )

        if (autoSplitLaunchStatus.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "自动分屏：$autoSplitLaunchStatus",
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 根据当前运行状态启动或停止附近打卡自动化服务
        Button(
            onClick = {
                if (isRecording) {
                    val serviceIntent = Intent(
                        context,
                        CruiseService::class.java
                    )

                    context.stopService(serviceIntent)
                    isRecording = false
                } else {
                    val coarseLocationGranted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED

                    val fineLocationGranted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED

                    if (coarseLocationGranted || fineLocationGranted) {
                        if (
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermissionLauncher.launch(
                                Manifest.permission.POST_NOTIFICATIONS
                            )
                        } else {
                            requestOverlayPermissionOrStart()
                        }
                    } else {
                        locationPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.ACCESS_FINE_LOCATION
                            )
                        )
                    }
                }
            }
        ) {
            Text(
                text = if (isRecording) {
                    "停止自动化"
                } else {
                    "开始自动化"
                }
            )
        }

        Spacer(
            modifier = Modifier.height(12.dp)
        )

        TextButton(
            onClick = {
                mapInstallationStatus = checkMapInstallationStatus(
                    context
                )
            }
        ) {
            Text(
                text = "重新检测地图安装"
            )
        }

        TextButton(
            onClick = {
                recordsDialogTitle = "本地今日打卡记录"

                displayedCheckInRecords = checkInRepository
                    .getAllRecords()
                    .filter { record ->
                        isTodayCheckIn(
                            record.checkInTimeMillis
                        )
                    }
                    .sortedByDescending { record ->
                        record.checkInTimeMillis
                    }

                showRecordsDialog = true
            }
        ) {
            Text(
                text = "查看本地今日打卡记录"
            )
        }

        TextButton(
            onClick = {
                recordsDialogTitle = "全部本地打卡记录"

                displayedCheckInRecords = checkInRepository
                    .getAllRecords()
                    .sortedByDescending { record ->
                        record.checkInTimeMillis
                    }

                showRecordsDialog = true
            }
        ) {
            Text(
                text = "查看全部本地打卡记录"
            )
        }

        TextButton(
            onClick = {
                showClearRecordsDialog = true
            }
        ) {
            Text(
                text = "清空本地记录与去重"
            )
        }

        TextButton(
            onClick = {
                try {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_ACCESSIBILITY_SETTINGS
                        )
                    )
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(
                        context,
                        "无法打开无障碍设置，请手动进入系统设置",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        ) {
            Text(
                text = "打开双地图诊断设置"
            )
        }

    }
}

/**
 * 在 Android Studio 设计预览中渲染主界面和右上角微光雷达组件。
 */
@Preview(showBackground = true)
@Composable
fun NearbyCheckInPreview() {
    NearbyCheckInTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            NearbyCheckInScreen()
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = 16.dp)) {
                GlowRadarOverlay()
            }
        }
    }
}
