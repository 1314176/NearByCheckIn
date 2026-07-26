package com.wu.nearbycheckin.platform.accessibility

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

    const val SECOND_CLICK_DELAY_MILLIS = 2_000L
}
