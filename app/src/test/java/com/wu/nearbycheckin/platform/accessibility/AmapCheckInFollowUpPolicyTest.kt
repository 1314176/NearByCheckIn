// @Time: 2026/07/26 23:49
// @Author: MrWu
// @File: AmapCheckInFollowUpPolicyTest.kt
// @Software: Android Studio


package com.wu.nearbycheckin.platform.accessibility

import com.wu.nearbycheckin.platform.maps.MapUiAction
import com.wu.nearbycheckin.platform.maps.MapUiPage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmapCheckInFollowUpPolicyTest {

    // 验证原子双击序列提交后直接进入结果识别阶段。
    @Test
    fun marksAtomicDoubleClickAsSubmitted() {
        assertEquals(
            AmapCheckInStage.SECOND_CLICK_SUBMITTED,
            AmapCheckInFollowUpPolicy.stageAfterAtomicDoubleClick()
        )
    }

    // 验证首次提交后的异步频率限制即可作为成功证据。
    @Test
    fun acceptsRateLimitAfterAnyCheckInSubmission() {
        assertFalse(
            AmapCheckInFollowUpPolicy.isSuccessfulRateLimit(
                page = MapUiPage.RATE_LIMITED,
                stage = AmapCheckInStage.NONE
            )
        )
        assertTrue(
            AmapCheckInFollowUpPolicy.isSuccessfulRateLimit(
                page = MapUiPage.RATE_LIMITED,
                stage = AmapCheckInStage.SECOND_CLICK_SUBMITTED
            )
        )
    }

    // 验证第二次点击已提交后不再执行第三次打卡动作。
    @Test
    fun stopsCheckInActionsAfterSecondSubmission() {
        assertTrue(
            AmapCheckInFollowUpPolicy.shouldExecuteCheckIn(
                AmapCheckInStage.NONE
            )
        )
        assertFalse(
            AmapCheckInFollowUpPolicy.shouldExecuteCheckIn(
                AmapCheckInStage.SECOND_CLICK_SUBMITTED
            )
        )
    }

    // 验证第二次点击后的未变化页面进入重复未知页保护。
    @Test
    fun guardsUnchangedCheckInPageAfterSecondSubmission() {
        assertEquals(
            MapUiPage.UNKNOWN,
            AmapCheckInFollowUpPolicy.loopGuardPage(
                page = MapUiPage.CHECK_IN,
                action = MapUiAction.CLICK_CHECK_IN,
                stage = AmapCheckInStage.SECOND_CLICK_SUBMITTED
            )
        )
        assertEquals(
            MapUiPage.CHECK_IN,
            AmapCheckInFollowUpPolicy.loopGuardPage(
                page = MapUiPage.CHECK_IN,
                action = MapUiAction.CLICK_CHECK_IN,
                stage = AmapCheckInStage.NONE
            )
        )
    }
}
