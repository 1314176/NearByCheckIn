package com.wu.nearbycheckin.platform.accessibility

import com.wu.nearbycheckin.platform.maps.MapUiAction
import com.wu.nearbycheckin.platform.maps.MapUiPage

enum class AmapCheckInStage {
    NONE,
    WAITING_SECOND_CLICK,
    SECOND_CLICK_SUBMITTED
}

object AmapCheckInFollowUpPolicy {

    // 返回一次成功“开始打卡”提交后的高德流程阶段。
    fun nextStageAfterSuccessfulCheckIn(
        currentStage: AmapCheckInStage
    ): AmapCheckInStage {
        return when (currentStage) {
            AmapCheckInStage.NONE ->
                AmapCheckInStage.WAITING_SECOND_CLICK
            AmapCheckInStage.WAITING_SECOND_CLICK,
            AmapCheckInStage.SECOND_CLICK_SUBMITTED ->
                AmapCheckInStage.SECOND_CLICK_SUBMITTED
        }
    }

    // 在等待第二次点击时返回固定两秒延迟。
    fun delayBeforeNextLoopMillis(
        stage: AmapCheckInStage,
        defaultDelayMillis: Long
    ): Long {
        return if (
            stage == AmapCheckInStage.WAITING_SECOND_CLICK
        ) {
            SECOND_CLICK_DELAY_MILLIS
        } else {
            defaultDelayMillis
        }
    }

    // 仅在第二次点击已提交后接受频率限制成功证据。
    fun isSuccessfulRateLimit(
        page: MapUiPage,
        stage: AmapCheckInStage
    ): Boolean {
        return page == MapUiPage.RATE_LIMITED &&
                stage == AmapCheckInStage.SECOND_CLICK_SUBMITTED
    }

    // 判断当前阶段是否仍允许提交“开始打卡”动作。
    fun shouldExecuteCheckIn(
        stage: AmapCheckInStage
    ): Boolean {
        return stage != AmapCheckInStage.SECOND_CLICK_SUBMITTED
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
