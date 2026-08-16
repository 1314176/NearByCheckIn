// @Time: 2026/07/20 18:29
// @Author: MrWu
// @File: MainUiEvent.kt
// @Software: Android Studio


package com.wu.nearbycheckin.presentation.main


import com.wu.nearbycheckin.core.domain.usecase.RecordQueryScope
import com.wu.nearbycheckin.core.model.MapProvider


/**
 * 表示主界面允许发送给 ViewModel 的全部用户事件。
 */
sealed interface MainUiEvent {

    data object StartCruise : MainUiEvent

    data object RetryStartCruise : MainUiEvent

    data object StopCruise : MainUiEvent

    data object AcceptPrivacy : MainUiEvent

    data object DismissPrivacy : MainUiEvent

    data object RefreshMapInstallation : MainUiEvent

    data object InitializeBothMaps : MainUiEvent

    data object InitializeBaiduMap : MainUiEvent

    data object InitializeAmap : MainUiEvent

    data class MapLaunchReported(
        val statusText: String
    ) : MainUiEvent

    data object CompleteBaiduPaneReplacement : MainUiEvent

    data class QueryRecords(
        val scope: RecordQueryScope
    ) : MainUiEvent

    data class QueryProviderRecords(
        val provider: MapProvider
    ) : MainUiEvent

    data object DismissRecords : MainUiEvent

    data object ShowRuntimeLogs : MainUiEvent

    data object DismissRuntimeLogs : MainUiEvent

    data object ClearRuntimeLogs : MainUiEvent

    data object RequestClearAll : MainUiEvent

    data object ConfirmClearAll : MainUiEvent

    data object DismissClearAll : MainUiEvent

    data object RequestClearBaiduCompletion : MainUiEvent

    data object ConfirmClearBaiduCompletion : MainUiEvent

    data object DismissClearBaiduCompletion : MainUiEvent

    data object OpenAccessibilitySettings : MainUiEvent

    data object ResumeBlockedTransaction : MainUiEvent
}
