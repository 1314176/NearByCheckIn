// @Time: 2026/07/20 18:29
// @Author: MrWu
// @File: MainUiState.kt
// @Software: Android Studio


package com.wu.nearbycheckin.presentation.main


import com.wu.nearbycheckin.core.model.AccessibilityDiagnostics
import com.wu.nearbycheckin.core.model.AutomationLogEntry
import com.wu.nearbycheckin.core.model.CheckInRecord


/**
 * 保存主界面纯渲染所需的全部不可变状态。
 */
data class MainUiState(
    val isCruiseRunning: Boolean = false,
    val baiduInstalled: Boolean = false,
    val amapInstalled: Boolean = false,
    val diagnostics: AccessibilityDiagnostics =
        AccessibilityDiagnostics(),
    val automationStatus: String = "",
    val activePlaceName: String = "",
    val transactionStatus: String = "",
    val baiduStatus: String = "",
    val amapStatus: String = "",
    val queueSize: Int = 0,
    val totalRecordCount: Int = 0,
    val displayedRecords: List<CheckInRecord> = emptyList(),
    val recordsDialogTitle: String = "本地打卡记录",
    val showRecordsDialog: Boolean = false,
    val displayedLogs: List<AutomationLogEntry> = emptyList(),
    val showLogsDialog: Boolean = false,
    val showClearRecordsDialog: Boolean = false,
    val showPrivacyDialog: Boolean = false,
    val mapChainStartupStatus: String = "",
    val isBaiduPaneReplacementPending: Boolean = false,
    val errorMessage: String = "",
) {

    val allMapsInstalled: Boolean
        get() = baiduInstalled && amapInstalled
}
