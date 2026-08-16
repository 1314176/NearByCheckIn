// @Time: 2026/07/26 23:50
// @Author: MrWu
// @File: AmapCheckInFollowUpPolicy.kt
// @Software: Android Studio


package com.wu.nearbycheckin.platform.accessibility

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

    // 首次打卡提交后即可接受频率限制证据，覆盖异步手势与提示事件竞态。
    fun isSuccessfulRateLimit(
        page: MapUiPage,
        stage: AmapCheckInStage
    ): Boolean {
        return page == MapUiPage.RATE_LIMITED &&
                stage != AmapCheckInStage.NONE
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

    const val SECOND_CLICK_DELAY_MILLIS = 2_000L
}
