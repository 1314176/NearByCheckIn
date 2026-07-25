// @Time: 2026/07/20 18:05
// @Author: MrWu
// @File: LegacyCheckInDataMigrator.kt
// @Software: Android Studio


package com.wu.nearbycheckin.data.repository


import android.content.Context

import com.wu.nearbycheckin.core.domain.repository.LegacyDataCleaner
import com.wu.nearbycheckin.core.model.AccessibilityDiagnostics
import com.wu.nearbycheckin.core.model.AutomationSnapshot
import com.wu.nearbycheckin.core.model.MapOperationStatus
import com.wu.nearbycheckin.data.legacy.AccessibilityDiagnosticsRepository
import com.wu.nearbycheckin.data.legacy.CheckInRepository


/**
 * 将旧记录与诊断数据幂等迁移到 V4 唯一事实来源。
 */
class LegacyCheckInDataMigrator(
    context: Context
) : LegacyDataCleaner {

    private val legacyCheckInRepository = CheckInRepository(
        context.applicationContext
    )
    private val legacyDiagnosticsRepository =
        AccessibilityDiagnosticsRepository(
            context.applicationContext
        )

    // 构造首次启动 V4 时使用的兼容快照。
    fun createInitialSnapshot(): AutomationSnapshot {
        val records = legacyCheckInRepository.getAllRecords()
        val legacyDualMapRecords =
            legacyCheckInRepository.getAllDualMapRecords()
        val completedKeys = buildSet {
            records.mapTo(this) { record ->
                record.poiUid
            }
            legacyDualMapRecords
                .filter { record ->
                    record.baiduResult.status.isSuccessful() &&
                            record.amapResult.status.isSuccessful()
                }
                .mapTo(this) { record ->
                    record.unifiedPlaceId
                }
        }.filter { key ->
            key.isNotBlank()
        }.toSet()
        val legacyDiagnostics =
            legacyDiagnosticsRepository.getLatestSnapshot()

        return AutomationSnapshot(
            records = records,
            legacyDualMapRecords = legacyDualMapRecords,
            completedPlaceKeys = completedKeys,
            diagnostics = legacyDiagnostics?.let { snapshot ->
                AccessibilityDiagnostics(
                    baiduReady = snapshot.baiduReady,
                    amapReady = snapshot.amapReady,
                    totalWindowCount = snapshot.totalWindowCount,
                    targetWindowCount = snapshot.targetWindowCount,
                    targetNodeCount = snapshot.targetNodeCount,
                    baiduClickableNodeCount =
                        snapshot.baiduClickableNodeCount,
                    baiduCandidateSummary =
                        snapshot.baiduCandidateSummary,
                    amapClickableNodeCount =
                        snapshot.amapClickableNodeCount,
                    amapCandidateSummary =
                        snapshot.amapCandidateSummary,
                    reason = snapshot.reason,
                    lastUpdatedMillis =
                        snapshot.lastUpdatedMillis
                )
            } ?: AccessibilityDiagnostics(),
            automationStatus =
                legacyDiagnosticsRepository.getAutoSplitLaunchStatus()
        )
    }

    // 清理用户确认范围内的旧版本兼容记录。
    override fun clearLegacyData(): Boolean {
        return legacyCheckInRepository.clearAllRecords()
    }

    // 判断旧地图端结果是否满足真正完成条件。
    private fun MapOperationStatus.isSuccessful(): Boolean {
        return this == MapOperationStatus.SUCCESS ||
                this == MapOperationStatus.ALREADY_COMPLETED
    }
}
