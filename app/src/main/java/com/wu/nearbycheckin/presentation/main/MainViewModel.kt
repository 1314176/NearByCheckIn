// @Time: 2026/07/20 18:29
// @Author: MrWu
// @File: MainViewModel.kt
// @Software: Android Studio


package com.wu.nearbycheckin.presentation.main


import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout

import com.wu.nearbycheckin.core.domain.gateway.CruisePrecheck
import com.wu.nearbycheckin.core.domain.usecase.ClearRecordsUseCase
import com.wu.nearbycheckin.core.domain.usecase.ClearScope
import com.wu.nearbycheckin.core.domain.usecase.QueryRecordsUseCase
import com.wu.nearbycheckin.core.domain.usecase.RecordQueryScope
import com.wu.nearbycheckin.core.domain.usecase.StartCruiseResult
import com.wu.nearbycheckin.core.domain.usecase.StartCruiseUseCase
import com.wu.nearbycheckin.core.domain.usecase.StopCruiseUseCase
import com.wu.nearbycheckin.core.domain.coordinator.CheckInCoordinator
import com.wu.nearbycheckin.core.domain.coordinator.ProviderAutomationCoordinator
import com.wu.nearbycheckin.core.model.AutomationLogLevel
import com.wu.nearbycheckin.core.model.AutomationSessionStatus
import com.wu.nearbycheckin.core.model.AutomationSnapshot
import com.wu.nearbycheckin.core.model.MapOperationStatus
import com.wu.nearbycheckin.core.model.MapProvider
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
    private val coordinator: CheckInCoordinator,
    private val providerCoordinator: ProviderAutomationCoordinator
) : ViewModel() {

    private val mutableUiState = MutableStateFlow(MainUiState())
    private val effectChannel = Channel<MainUiEffect>(
        capacity = Channel.BUFFERED
    )
    private val startRequestGate = StartRequestGate()
    private var startRequestJob: Job? = null

    val uiState: StateFlow<MainUiState> = mutableUiState.asStateFlow()
    val effects = effectChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            runPlatformCall("初始化启动状态") {
                startCruiseUseCase.initializeMainEntry()
            }
            refreshPrecheck()
        }
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
                startCruise()
            MainUiEvent.StopCruise ->
                stopCruise()
            MainUiEvent.AcceptPrivacy -> {
                mutableUiState.value = mutableUiState.value.copy(
                    showPrivacyDialog = false
                )
                startCruise(grantPrivacyConsent = true)
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
                mutableUiState.value = mutableUiState.value.copy(
                    mapChainStartupStatus = event.statusText
                )
                viewModelScope.launch {
                    runPlatformCall("记录分屏启动状态") {
                        repository.updateAutomationStatus(
                            statusText = event.statusText,
                            source = "分屏启动",
                            level = event.statusText.toLogLevel()
                        )
                    }
                }
            }
            MainUiEvent.CompleteBaiduPaneReplacement -> {
                mutableUiState.value = mutableUiState.value.copy(
                    isBaiduPaneReplacementPending = false
                )
            }
            is MainUiEvent.QueryRecords ->
                showRecords(event.scope)
            is MainUiEvent.QueryProviderRecords ->
                showProviderRecords(event.provider)
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
            MainUiEvent.RequestClearBaiduCompletion -> {
                mutableUiState.value = mutableUiState.value.copy(
                    showClearBaiduCompletionDialog = true
                )
            }
            MainUiEvent.ConfirmClearBaiduCompletion ->
                clearBaiduCompletion()
            MainUiEvent.DismissClearBaiduCompletion -> {
                mutableUiState.value = mutableUiState.value.copy(
                    showClearBaiduCompletionDialog = false
                )
            }
            MainUiEvent.OpenAccessibilitySettings ->
                sendEffect(MainUiEffect.OpenAccessibilitySettings)
            MainUiEvent.ResumeBlockedTransaction ->
                resumeBlockedTransaction()
        }
    }

    // 在后台线程执行一次启动请求并合并连续点击。
    private fun startCruise(grantPrivacyConsent: Boolean = false) {
        if (!startRequestGate.tryAcquire()) {
            emitEffect("启动检查正在进行，请稍候")
            return
        }
        startRequestJob = viewModelScope.launch {
            try {
                startWithAccessibilityConnectionRetry(
                    grantPrivacyConsent = grantPrivacyConsent
                )?.let(::handleStartResult)
            } finally {
                startRequestGate.release()
                startRequestJob = null
            }
        }
    }

    // 给刚完成系统绑定的无障碍服务短暂重试窗口，避免误要求再次开启权限。
    private suspend fun startWithAccessibilityConnectionRetry(
        grantPrivacyConsent: Boolean
    ): StartCruiseResult? {
        var result = runPlatformCall("启动巡航自动化") {
            if (grantPrivacyConsent) {
                startCruiseUseCase.grantPrivacyConsentAndStart()
            } else {
                startCruiseUseCase()
            }
        } ?: return null

        repeat(ACCESSIBILITY_CONNECTION_RETRY_COUNT) {
            if (result !is StartCruiseResult.AccessibilityServiceRequired) {
                return result
            }
            delay(ACCESSIBILITY_CONNECTION_RETRY_DELAY_MILLIS)
            result = runPlatformCall("等待无障碍服务连接") {
                startCruiseUseCase()
            } ?: return null
        }
        return result
    }

    // 取消尚未完成的启动后在后台停止巡航服务和事务。
    private fun stopCruise() {
        val activeStartRequest = startRequestJob
        activeStartRequest?.cancel()
        viewModelScope.launch {
            activeStartRequest?.join()
            runPlatformCall("停止巡航自动化") {
                stopCruiseUseCase()
            } ?: return@launch
            emitEffect("巡航自动化已停止")
        }
    }

    // 在后台恢复阻断事务，避免持久化读写占用界面线程。
    private fun resumeBlockedTransaction() {
        viewModelScope.launch {
            val resumed = runPlatformCall("恢复阻断事务") {
                providerCoordinator.manualResume(MapProvider.BAIDU) ||
                        coordinator.manualResume()
            } ?: return@launch
            emitEffect(
                if (resumed) {
                    "已恢复被阻断的事务"
                } else {
                    "当前没有可恢复的阻断事务"
                }
            )
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
            StartCruiseResult.OverlayPermissionRequired ->
                sendEffect(MainUiEffect.RequestOverlayPermission)
            StartCruiseResult.AccessibilityServiceRequired -> {
                emitEffect(
                    "无障碍服务未真实连接，请在系统设置中重新启用一次"
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
        viewModelScope.launch {
            val precheck = runPlatformCall("刷新启动条件") {
                startCruiseUseCase.inspectPrecheck()
            } ?: return@launch
            mutableUiState.value = mutableUiState.value.withPrecheck(
                precheck
            )
        }
    }

    // 校验双地图安装状态并请求前台创建相邻窗口。
    private fun requestDualMapSplit() {
        viewModelScope.launch {
            val precheck = runPlatformCall("检查双地图安装状态") {
                startCruiseUseCase.inspectPrecheck()
            } ?: return@launch
            if (!precheck.allMapsInstalled) {
                emitEffect("百度地图或高德地图未安装，无法创建双地图分屏")
                return@launch
            }
            mutableUiState.value = mutableUiState.value.copy(
                isBaiduPaneReplacementPending = true
            )
            sendEffect(MainUiEffect.OpenDualMapSplit)
        }
    }

    // 请求 Activity 以前台身份打开指定地图应用。
    private fun requestMapLaunch(packageNames: List<String>) {
        viewModelScope.launch {
            val precheck = runPlatformCall("检查地图安装状态") {
                startCruiseUseCase.inspectPrecheck()
            } ?: return@launch
            val installedPackages = packageNames.filter { packageName ->
                when (packageName) {
                    BAIDU_MAP_PACKAGE_NAME -> precheck.baiduInstalled
                    AMAP_PACKAGE_NAME -> precheck.amapInstalled
                    else -> false
                }
            }
            if (installedPackages.size != packageNames.size) {
                emitEffect("目标地图未安装，无法初始化链式启动授权")
                return@launch
            }

            sendEffect(
                MainUiEffect.OpenMapApplications(installedPackages)
            )
        }
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

    // 按 Provider 筛选并展示结构化打卡记录和结果证据。
    private fun showProviderRecords(provider: MapProvider) {
        mutableUiState.value = mutableUiState.value.copy(
            displayedRecords = repository.currentSnapshot().records
                .filter { record -> record.provider == provider }
                .sortedByDescending { record -> record.checkInTimeMillis },
            recordsDialogTitle = when (provider) {
                MapProvider.BAIDU -> "百度地图打卡记录"
                MapProvider.AMAP -> "高德地图打卡记录"
            },
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

    // 二次确认后只清除百度完成键和终结任务。
    private fun clearBaiduCompletion() {
        val result = clearRecordsUseCase(
            scope = ClearScope.BAIDU_PERMANENT_COMPLETION_KEYS,
            confirmed = true
        )
        mutableUiState.value = mutableUiState.value.copy(
            showClearBaiduCompletionDialog = false
        )
        emitEffect(
            if (result.cleared) {
                "百度完成键已清空，候选将重新读取"
            } else {
                "百度完成键清空失败"
            }
        )
    }

    // 限时在 IO 线程执行系统或持久化调用并把异常转换为可见提示。
    private suspend fun <T> runPlatformCall(
        actionName: String,
        block: () -> T
    ): T? {
        return try {
            withTimeout(PLATFORM_CALL_TIMEOUT_MILLIS) {
                runInterruptible(Dispatchers.IO, block)
            }
        } catch (_: TimeoutCancellationException) {
            emitEffect("$actionName 超时，已安全终止本次请求，请重试")
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: RuntimeException) {
            val reason = error.message.orEmpty().ifBlank {
                error::class.java.simpleName
            }
            emitEffect("$actionName 失败：$reason")
            null
        }
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
        val providerTask = snapshot.activeProviderTask
        return copy(
            isCruiseRunning =
                snapshot.session?.status ==
                        AutomationSessionStatus.RUNNING,
            diagnostics = snapshot.diagnostics,
            automationStatus = snapshot.automationStatus,
            activePlaceName = providerTask?.candidate?.name
                ?: transaction?.candidate?.name.orEmpty(),
            transactionStatus =
                transaction?.state?.status?.name.orEmpty(),
            baiduStatus =
                transaction?.state?.baiduResult?.status.toStatusText(),
            amapStatus =
                transaction?.state?.amapResult?.status.toStatusText(),
            queueSize = snapshot.providerQueue(MapProvider.BAIDU).size,
            baiduProviderState = snapshot.providerRuntime.baiduState.name,
            baiduCandidateCount = snapshot.providerCandidates.count {
                candidate -> candidate.provider == MapProvider.BAIDU
            },
            baiduCompletedCount = snapshot.providerCompletedKeys.count {
                key -> key.provider == MapProvider.BAIDU
            },
            baiduDailyVerifiedSuccessCount = snapshot.providerRuntime
                .baiduDailyVerifiedSuccessCount,
            baiduTotalVerifiedSuccessCount = snapshot.providerRuntime
                .baiduTotalVerifiedSuccessCount,
            baiduObservedCheckInCount = snapshot.providerRuntime
                .baiduObservedCheckInCount,
            baiduLastVerifiedSuccessEvidence = snapshot.providerRuntime
                .baiduLastVerifiedSuccessEvidence,
            baiduDailyQuotaBlocked = snapshot.providerRuntime
                .baiduDailyQuotaResetAtMillis > System.currentTimeMillis(),
            baiduDailyQuotaResetAtMillis = snapshot.providerRuntime
                .baiduDailyQuotaResetAtMillis,
            baiduListFingerprint =
                snapshot.providerRuntime.baiduListFingerprint,
            activeProviderCandidate = providerTask?.candidate?.name.orEmpty(),
            pluginPage = snapshot.providerRuntime.baiduPluginPage,
            pluginAction = snapshot.providerRuntime.baiduPluginAction,
            pluginEvidence = snapshot.providerRuntime.baiduPluginEvidence,
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
        const val ACCESSIBILITY_CONNECTION_RETRY_COUNT = 4
        const val ACCESSIBILITY_CONNECTION_RETRY_DELAY_MILLIS = 500L
        const val PLATFORM_CALL_TIMEOUT_MILLIS = 10_000L
    }
}

/**
 * 合并并发启动请求，确保同一时间只有一条启动链路修改会话状态。
 */
internal class StartRequestGate {

    private var acquired = false

    // 尝试获得唯一启动权并拒绝重复请求。
    @Synchronized
    fun tryAcquire(): Boolean {
        if (acquired) {
            return false
        }
        acquired = true
        return true
    }

    // 释放启动权并允许失败或停止后的后续重试。
    @Synchronized
    fun release() {
        acquired = false
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
    private val coordinator: CheckInCoordinator,
    private val providerCoordinator: ProviderAutomationCoordinator
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
            coordinator = coordinator,
            providerCoordinator = providerCoordinator
        ) as T
    }
}
