// @Time: 2026/07/26 23:50
// @Author: MrWu
// @File: AmapCheckInFollowUpPolicy.kt
// @Software: Android Studio


package com.wu.nearbycheckin.platform.accessibility

import com.wu.nearbycheckin.core.model.MapOperationEvidence
import com.wu.nearbycheckin.core.model.MapOperationStatus
import com.wu.nearbycheckin.platform.maps.MapUiAction
import com.wu.nearbycheckin.platform.maps.MapUiPage

enum class AmapCheckInStage {
    NONE,
    SECOND_CLICK_SUBMITTED
}

object AmapCheckInFollowUpPolicy {

    // 原子双击序列提交后直接进入结果识别阶段。
    fun stageAfterAtomicDoubleClick(): AmapCheckInStage {
        return AmapCheckInStage.SECOND_CLICK_SUBMITTED
    }

    // 判断当前阶段是否仍允许提交“开始打卡”动作。
    fun shouldExecuteCheckIn(
        stage: AmapCheckInStage
    ): Boolean {
        return stage == AmapCheckInStage.NONE
    }

    // 把第二次点击后未变化的打卡页纳入重复未知页保护。
    fun loopGuardPage(
        page: MapUiPage,
        action: MapUiAction?,
        stage: AmapCheckInStage
    ): MapUiPage {
        return if (
            stage == AmapCheckInStage.SECOND_CLICK_SUBMITTED &&
            page == MapUiPage.CHECK_IN &&
            action == MapUiAction.CLICK_CHECK_IN
        ) {
            MapUiPage.UNKNOWN
        } else {
            page
        }
    }

    // 第二次点击后的真实频率提示仅用于诊断，最终成功必须等待地点数量增长。
    fun resolveProviderEvidence(
        evidence: MapOperationEvidence,
        secondClickSubmitted: Boolean,
        transientMessage: String = ""
    ): MapOperationEvidence {
        if (
            !secondClickSubmitted ||
            evidence.resultStatus != MapOperationStatus.UNKNOWN
        ) {
            return evidence
        }
        val rateLimitEvidence = transientMessage
            .takeIf(::containsRateLimitMarker)
            .orEmpty()
        if (rateLimitEvidence.isBlank()) {
            return evidence
        }
        return evidence.copy(
            message = "高德第二次点击收到真实频率提示，但地点总数未增加，" +
                    "继续等待最终成功证据：$rateLimitEvidence"
        )
    }

    // 判断文本是否包含高德打卡频率提示。
    private fun containsRateLimitMarker(value: String): Boolean {
        return RATE_LIMIT_MARKERS.any(value::contains)
    }

    const val SECOND_CLICK_DELAY_MILLIS = 2_000L
    private val RATE_LIMIT_MARKERS = listOf(
        "打卡太频繁",
        "操作太频繁",
        "操作频繁"
    )
}
