// @Time: 2026/07/20 18:00
// @Author: MrWu
// @File: CheckInTransactionRepository.kt
// @Software: Android Studio


package com.wu.nearbycheckin.domain.repository


import kotlinx.coroutines.flow.StateFlow

import com.wu.nearbycheckin.domain.model.AccessibilityDiagnostics
import com.wu.nearbycheckin.domain.model.AutomationLogLevel
import com.wu.nearbycheckin.domain.model.AutomationSnapshot
import com.wu.nearbycheckin.domain.model.MapCommand


/**
 * 定义自动化快照唯一事实来源的原子读写契约。
 */
interface CheckInTransactionRepository {

    val automationSnapshot: StateFlow<AutomationSnapshot>

    // 返回当前内存中的一致性快照。
    fun currentSnapshot(): AutomationSnapshot

    // 在同一个持久化临界区内更新完整快照。
    fun updateSnapshot(
        transform: (AutomationSnapshot) -> AutomationSnapshot
    ): AutomationSnapshot

    // 原子领取一个到期且尚未完成的地图命令。
    fun claimNextCommand(
        workerId: String,
        nowMillis: Long,
        leaseDurationMillis: Long
    ): MapCommand?

    // 原子更新无障碍诊断状态。
    fun updateDiagnostics(diagnostics: AccessibilityDiagnostics)

    // 原子更新 UI、通知和悬浮窗共同显示的自动化状态。
    fun updateAutomationStatus(
        statusText: String,
        source: String = "自动化",
        level: AutomationLogLevel = AutomationLogLevel.INFO
    )

    // 原子保存一次待无障碍服务消费的分屏请求。
    fun requestAutoSplitLaunch(requestedAtMillis: Long)

    // 原子消费一次分屏请求并返回请求时间。
    fun consumeAutoSplitLaunchRequest(): Long?

    // 清除尚未被消费的分屏请求。
    fun clearPendingAutoSplitLaunchRequest()

    // 清空应用内持久化运行日志。
    fun clearRuntimeLogs()

    // 清空事务、候选、记录和永久去重集合。
    fun clearAll(): Boolean
}
