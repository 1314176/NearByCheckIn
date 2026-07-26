package com.wu.nearbycheckin.platform.accessibility

import com.wu.nearbycheckin.platform.maps.MapUiPage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmapCheckInFollowUpPolicyTest {

    // 验证第一次成功提交后进入等待第二次点击阶段。
    @Test
    fun waitsForSecondClickAfterFirstSubmission() {
        assertEquals(
            AmapCheckInStage.WAITING_SECOND_CLICK,
            AmapCheckInFollowUpPolicy.nextStageAfterSuccessfulCheckIn(
                AmapCheckInStage.NONE
            )
        )
    }

    // 验证第二次成功提交后进入结果识别阶段。
    @Test
    fun marksSecondClickAsSubmitted() {
        assertEquals(
            AmapCheckInStage.SECOND_CLICK_SUBMITTED,
            AmapCheckInFollowUpPolicy.nextStageAfterSuccessfulCheckIn(
                AmapCheckInStage.WAITING_SECOND_CLICK
            )
        )
    }

    // 验证第二次点击固定等待两秒。
    @Test
    fun usesTwoSecondDelayBeforeSecondClick() {
        assertEquals(
            2_000L,
            AmapCheckInFollowUpPolicy.delayBeforeNextLoopMillis(
                AmapCheckInStage.WAITING_SECOND_CLICK,
                defaultDelayMillis = 1_500L
            )
        )
    }

    // 验证第二次点击完成后恢复普通页面识别延迟。
    @Test
    fun restoresDefaultDelayAfterSecondClick() {
        assertEquals(
            1_500L,
            AmapCheckInFollowUpPolicy.delayBeforeNextLoopMillis(
                AmapCheckInStage.SECOND_CLICK_SUBMITTED,
                defaultDelayMillis = 1_500L
            )
        )
    }

    // 验证只有第二次点击已提交后才接受频率限制成功证据。
    @Test
    fun acceptsRateLimitOnlyAfterSecondClick() {
        assertFalse(
            AmapCheckInFollowUpPolicy.isSuccessfulRateLimit(
                page = MapUiPage.RATE_LIMITED,
                stage = AmapCheckInStage.NONE
            )
        )
        assertFalse(
            AmapCheckInFollowUpPolicy.isSuccessfulRateLimit(
                page = MapUiPage.RATE_LIMITED,
                stage = AmapCheckInStage.WAITING_SECOND_CLICK
            )
        )
        assertTrue(
            AmapCheckInFollowUpPolicy.isSuccessfulRateLimit(
                page = MapUiPage.RATE_LIMITED,
                stage = AmapCheckInStage.SECOND_CLICK_SUBMITTED
            )
        )
    }
}
