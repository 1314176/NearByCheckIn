// @Time: 2026/07/26 04:44
// @Author: MrWu
// @File: MapUiExecutionPolicy.kt
// @Software: Android Studio


package com.wu.nearbycheckin.platform.accessibility

import com.wu.nearbycheckin.core.model.MapOperationStatus
import com.wu.nearbycheckin.platform.maps.MapUiAction
import com.wu.nearbycheckin.platform.maps.MapUiPage

enum class MapUiExecutionOutcome {
    CONTINUE,
    SUCCESS,
    ALREADY_COMPLETED,
    BLOCKED,
    UNSUPPORTED,
    TIMEOUT
}

class MapUiExecutionPolicy(
    private val maxActionCount: Int = 12,
    private val maxRepeatedUnknownCount: Int = 3
) {

    // 根据页面和循环计数决定继续执行或结束命令。
    fun outcomeFor(
        page: MapUiPage,
        actionCount: Int,
        repeatedUnknownCount: Int
    ): MapUiExecutionOutcome {
        return when {
            page == MapUiPage.SUCCESS ->
                MapUiExecutionOutcome.SUCCESS
            page == MapUiPage.ALREADY_COMPLETED ->
                MapUiExecutionOutcome.ALREADY_COMPLETED
            page == MapUiPage.BLOCKED ->
                MapUiExecutionOutcome.BLOCKED
            repeatedUnknownCount >= maxRepeatedUnknownCount ->
                MapUiExecutionOutcome.UNSUPPORTED
            actionCount >= maxActionCount ->
                MapUiExecutionOutcome.TIMEOUT
            else ->
                MapUiExecutionOutcome.CONTINUE
        }
    }

    // 仅在相同未知页面指纹连续出现时累计重复次数。
    fun repeatedUnknownCountFor(
        page: MapUiPage,
        fingerprint: String,
        lastFingerprint: String,
        repeatedUnknownCount: Int
    ): Int {
        if (
            page != MapUiPage.UNKNOWN &&
            page != MapUiPage.RATE_LIMITED
        ) {
            return 0
        }
        return if (
            lastFingerprint.isNotEmpty() &&
            fingerprint == lastFingerprint
        ) {
            repeatedUnknownCount + 1
        } else {
            0
        }
    }

    // 仅在可点击动作成功后增加动作次数。
    fun actionCountAfterSuccessfulAction(
        actionCount: Int,
        action: MapUiAction?
    ): Int {
        return when (action) {
            null,
            MapUiAction.WAIT,
            MapUiAction.BLOCK -> actionCount
            else -> actionCount + 1
        }
    }
}

// 把页面循环终态转换成领域地图结果。
fun MapUiExecutionOutcome.toOperationStatus(): MapOperationStatus {
    return when (this) {
        MapUiExecutionOutcome.SUCCESS ->
            MapOperationStatus.SUCCESS
        MapUiExecutionOutcome.ALREADY_COMPLETED ->
            MapOperationStatus.ALREADY_COMPLETED
        MapUiExecutionOutcome.BLOCKED ->
            MapOperationStatus.BLOCKED
        MapUiExecutionOutcome.UNSUPPORTED ->
            MapOperationStatus.UNSUPPORTED
        MapUiExecutionOutcome.TIMEOUT ->
            MapOperationStatus.TIMEOUT
        MapUiExecutionOutcome.CONTINUE ->
            MapOperationStatus.UNKNOWN
    }
}
