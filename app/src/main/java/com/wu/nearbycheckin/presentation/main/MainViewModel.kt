// @Time: 2026/07/20 18:29
// @Author: MrWu
// @File: MainViewModel.kt
// @Software: Android Studio


package com.wu.nearbycheckin.presentation.main


import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

import com.wu.nearbycheckin.core.domain.gateway.CruisePrecheck
import com.wu.nearbycheckin.core.domain.usecase.ClearRecordsUseCase
import com.wu.nearbycheckin.core.domain.usecase.ClearScope
import com.wu.nearbycheckin.core.domain.usecase.QueryRecordsUseCase
import com.wu.nearbycheckin.core.domain.usecase.RecordQueryScope
import com.wu.nearbycheckin.core.domain.usecase.StartCruiseResult
import com.wu.nearbycheckin.core.domain.usecase.StartCruiseUseCase
import com.wu.nearbycheckin.core.domain.usecase.StopCruiseUseCase
import com.wu.nearbycheckin.core.domain.coordinator.CheckInCoordinator
import com.wu.nearbycheckin.core.model.AutomationLogLevel
import com.wu.nearbycheckin.core.model.AutomationSessionStatus
import com.wu.nearbycheckin.core.model.AutomationSnapshot
import com.wu.nearbycheckin.core.model.MapOperationStatus
import com.wu.nearbycheckin.core.domain.repository.CheckInTransactionRepository


/**
 * 将 Repository Flow 和 UI 事件映射为唯一 MainUiState。
 */
class MainViewModel(
    private val repository: CheckInTransactionRepository,
    private val startCruiseUseCase: StartCruiseUseCase,
    private val stopCruiseUseCase: StopCruiseUseCase,
    private val queryRecordsUseCase: QueryRecordsUseCase,
    private val clearRecordsUseCase: ClearRecordsUseCase,
    private val coordinator: CheckInCoordinator
) : ViewModel() {

    private val mutableUiState = MutableStateFlow(MainUiState())
    private val effectChannel = Channel<MainUiEffect>(
        capacity = Channel.BUFFERED
    )

    val uiState: StateFlow<MainUiState> = mutableUiState.asStateFlow()
    val effects = effectChannel.receiveAsFlow()

    init {
        startCruiseUseCase.initializeMainEntry()
        refreshPrecheck()
        viewModelScope.launch {
            repository.automationSnapshot.collect { snapshot ->
                mutableUiState.value =
                    mutableUiState.value.withSnapshot(snapshot)
            }
        }
    }

    // 将用户事件串行转换为用例调用、状态或一次性 Effect。
    fun onEvent(event: MainUiEvent) {
        when (event) {
            MainUiEvent.StartCruise,
            MainUiEvent.RetryStartCruise ->
                handleStartResult(startCruiseUseCase())
            MainUiEvent.StopCruise -> {
                stopCruiseUseCase()
                emitEffect("巡航自动化已停止")
            }
            MainUiEvent.AcceptPrivacy -> {
                mutableUiState.value = mutableUiState.value.copy(
                    showPrivacyDialog = false
                )
                handleStartResult(
                    startCruiseUseCase.grantPrivacyConsentAndStart()
                )
            }
            MainUiEvent.DismissPrivacy -> {
                mutableUiState.value = mutableUiState.value.copy(
                    showPrivacyDialog = false
                )
            }
            MainUiEvent.RefreshMapInstallation ->
                refreshPrecheck()
            MainUiEvent.InitializeBothMaps ->
                requestDualMapSplit()
            MainUiEvent.InitializeBaiduMap ->
                requestMapLaunch(listOf(BAIDU_MAP_PACKAGE_NAME))
            MainUiEvent.InitializeAmap ->
                requestMapLaunch(listOf(AMAP_PACKAGE_NAME))
            is MainUiEvent.MapLaunchReported -> {
                repository.updateAutomationStatus(
                    statusText = event.statusText,
                    source = "分屏启动",
                    level = event.statusText.toLogLevel()
                )
                mutableUiState.value = mutableUiState.value.copy(
                    mapChainStartupStatus = event.statusText
                )
            }
            MainUiEvent.CompleteBaiduPaneReplacement -> {
                mutableUiState.value = mutableUiState.value.copy(
                    isBaiduPaneReplacementPending = false
                )
            }
            is MainUiEvent.QueryRecords ->
                showRecords(event.scope)
            MainUiEvent.DismissRecords -> {
                mutableUiState.value = mutableUiState.value.copy(
                    showRecordsDialog = false
                )
            }
            MainUiEvent.ShowRuntimeLogs ->
                showRuntimeLogs()
            MainUiEvent.DismissRuntimeLogs -> {
                val shouldRestoreDualMapSplit =
                    mutableUiState.value.isCruiseRunning &&
                            mutableUiState.value.allMapsInstalled
                mutableUiState.value = mutableUiState.value.copy(
                    showLogsDialog = false
                )
                if (shouldRestoreDualMapSplit) {
                    requestDualMapSplit()
                }
            }
            MainUiEvent.ClearRuntimeLogs -> {
                repository.clearRuntimeLogs()
                mutableUiState.value = mutableUiState.value.copy(
                    displayedLogs = emptyList()
                )
            }
            MainUiEvent.RequestClearAll -> {
                mutableUiState.value = mutableUiState.value.copy(
                    showClearRecordsDialog = true
                )
            }
            MainUiEvent.ConfirmClearAll ->
                clearAllRecords()
            MainUiEvent.DismissClearAll -> {
                mutableUiState.value = mutableUiState.value.copy(
                    showClearRecordsDialog = false
                )
            }
            MainUiEvent.OpenAccessibilitySettings ->
                sendEffect(MainUiEffect.OpenAccessibilitySettings)
            MainUiEvent.ResumeBlockedTransaction -> {
                val resumed = coordinator.manualResume()
                emitEffect(
                    if (resumed) {
                        "已恢复被阻断的事务"
                    } else {
                        "当前没有可恢复的阻断事务"
                    }
                )
            }
        }
    }

    // 将启动用例结果映射为对话框或系统导航 Effect。
    private fun handleStartResult(result: StartCruiseResult) {
        refreshPrecheck()
        when (result) {
            StartCruiseResult.PrivacyConsentRequired -> {
                mutableUiState.value = mutableUiState.value.copy(
                    showPrivacyDialog = true
                )
            }
            StartCruiseResult.LocationPermissionRequired ->
                sendEffect(MainUiEffect.RequestLocationPermission)
            StartCruiseResult.NotificationPermissionRequired ->
                sendEffect(
                    MainUiEffect.RequestNotificationPermission
                )
            StartCruiseResult.OverlayPermissionRequired ->
                sendEffect(MainUiEffect.RequestOverlayPermission)
            StartCruiseResult.AccessibilityServiceRequired -> {
                emitEffect(
                    "请先启用 NearbyCheckIn 双地图诊断无障碍服务"
                )
                sendEffect(MainUiEffect.OpenAccessibilitySettings)
            }
            is StartCruiseResult.MapsMissing -> {
                emitEffect(
                    "请先安装百度地图和高德地图，再启动双地图自动化"
                )
            }
            StartCruiseResult.MapSdkInitializationFailed ->
                emitEffect("百度地图服务初始化失败，请检查网络和AK配置")
            is StartCruiseResult.Started -> {
                emitEffect("巡航自动化已启动")
                if (result.shouldOpenBaiduForSplit) {
                    requestDualMapSplit()
                }
            }
        }
    }

    // 刷新地图安装状态并保持其他 UI 状态不变。
    private fun refreshPrecheck() {
        val precheck = startCruiseUseCase.inspectPrecheck()
        mutableUiState.value = mutableUiState.value.withPrecheck(
            precheck
        )
    }

    // 校验双地图安装状态并请求前台创建相邻窗口。
    private fun requestDualMapSplit() {
        val precheck = startCruiseUseCase.inspectPrecheck()
        if (!precheck.allMapsInstalled) {
            emitEffect("百度地图或高德地图未安装，无法创建双地图分屏")
            return
        }
        mutableUiState.value = mutableUiState.value.copy(
            isBaiduPaneReplacementPending = true
        )
        sendEffect(MainUiEffect.OpenDualMapSplit)
    }

    // 请求 Activity 以前台身份打开指定地图应用。
    private fun requestMapLaunch(packageNames: List<String>) {
        val precheck = startCruiseUseCase.inspectPrecheck()
        val installedPackages = packageNames.filter { packageName ->
            when (packageName) {
                BAIDU_MAP_PACKAGE_NAME -> precheck.baiduInstalled
                AMAP_PACKAGE_NAME -> precheck.amapInstalled
                else -> false
            }
        }
        if (installedPackages.size != packageNames.size) {
            emitEffect("目标地图未安装，无法初始化链式启动授权")
            return
        }

        sendEffect(
            MainUiEffect.OpenMapApplications(installedPackages)
        )
    }

    // 查询指定范围记录并打开只读记录对话框。
    private fun showRecords(scope: RecordQueryScope) {
        val title = if (scope == RecordQueryScope.TODAY) {
            "本地今日打卡记录"
        } else {
            "全部本地打卡记录"
        }
        mutableUiState.value = mutableUiState.value.copy(
            displayedRecords = queryRecordsUseCase(scope),
            recordsDialogTitle = title,
            showRecordsDialog = true
        )
    }

    // 读取最新持久化日志并按时间倒序打开日志对话框。
    private fun showRuntimeLogs() {
        mutableUiState.value = mutableUiState.value.copy(
            displayedLogs = repository.currentSnapshot()
                .runtimeLogs
                .asReversed(),
            showLogsDialog = true
        )
    }

    // 在用户二次确认后清空新旧记录与永久去重数据。
    private fun clearAllRecords() {
        val result = clearRecordsUseCase(
            scope = ClearScope.ALL,
            confirmed = true
        )
        mutableUiState.value = mutableUiState.value.copy(
            showClearRecordsDialog = false
        )
        emitEffect(
            if (result.cleared) {
                "本地记录和双地图事务记录已清空"
            } else {
                "本地记录清空失败"
            }
        )
    }

    // 把普通提示转换为一次性 Toast Effect。
    private fun emitEffect(message: String) {
        sendEffect(MainUiEffect.ShowToast(message))
    }

    // 非阻塞发送一次性 UI Effect。
    private fun sendEffect(effect: MainUiEffect) {
        effectChannel.trySend(effect)
    }

    // 将平台预检状态映射为 UI 安装状态。
    private fun MainUiState.withPrecheck(
        precheck: CruisePrecheck
    ): MainUiState {
        return copy(
            baiduInstalled = precheck.baiduInstalled,
            amapInstalled = precheck.amapInstalled
        )
    }

    // 将唯一事实来源快照映射为不可变 UI 状态。
    private fun MainUiState.withSnapshot(
        snapshot: AutomationSnapshot
    ): MainUiState {
        val transaction = snapshot.activeTransaction
        return copy(
            isCruiseRunning =
                snapshot.session?.status ==
                        AutomationSessionStatus.RUNNING,
            diagnostics = snapshot.diagnostics,
            automationStatus = snapshot.automationStatus,
            activePlaceName = transaction?.candidate?.name.orEmpty(),
            transactionStatus =
                transaction?.state?.status?.name.orEmpty(),
            baiduStatus =
                transaction?.state?.baiduResult?.status.toStatusText(),
            amapStatus =
                transaction?.state?.amapResult?.status.toStatusText(),
            queueSize = snapshot.queuedTransactions.size,
            totalRecordCount = snapshot.records.size,
            displayedLogs = if (showLogsDialog) {
                snapshot.runtimeLogs.asReversed()
            } else {
                displayedLogs
            }
        )
    }

    // 根据状态文本中的失败语义确定日志级别。
    private fun String.toLogLevel(): AutomationLogLevel {
        return when {
            contains("失败") ||
                    contains("错误") ->
                AutomationLogLevel.ERROR
            contains("未") ||
                    contains("等待") ->
                AutomationLogLevel.WARNING
            else -> AutomationLogLevel.INFO
        }
    }

    // 将可空地图端状态转换为简洁文本。
    private fun MapOperationStatus?.toStatusText(): String {
        return this?.name.orEmpty()
    }

    private companion object {
        const val BAIDU_MAP_PACKAGE_NAME = "com.baidu.BaiduMap"
        const val AMAP_PACKAGE_NAME = "com.autonavi.minimap"
    }
}

/**
 * 使用 AppContainer 中的稳定依赖创建 MainViewModel。
 */
class MainViewModelFactory(
    private val repository: CheckInTransactionRepository,
    private val startCruiseUseCase: StartCruiseUseCase,
    private val stopCruiseUseCase: StopCruiseUseCase,
    private val queryRecordsUseCase: QueryRecordsUseCase,
    private val clearRecordsUseCase: ClearRecordsUseCase,
    private val coordinator: CheckInCoordinator
) : ViewModelProvider.Factory {

    // 创建 MainViewModel 并拒绝未知类型。
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (!modelClass.isAssignableFrom(MainViewModel::class.java)) {
            throw IllegalArgumentException(
                "不支持的 ViewModel 类型：${modelClass.name}"
            )
        }

        @Suppress("UNCHECKED_CAST")
        return MainViewModel(
            repository = repository,
            startCruiseUseCase = startCruiseUseCase,
            stopCruiseUseCase = stopCruiseUseCase,
            queryRecordsUseCase = queryRecordsUseCase,
            clearRecordsUseCase = clearRecordsUseCase,
            coordinator = coordinator
        ) as T
    }
}
