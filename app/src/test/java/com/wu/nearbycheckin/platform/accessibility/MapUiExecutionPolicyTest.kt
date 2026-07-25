package com.wu.nearbycheckin.platform.accessibility

import com.wu.nearbycheckin.core.model.MapOperationStatus
import com.wu.nearbycheckin.platform.maps.MapUiAction
import com.wu.nearbycheckin.platform.maps.MapUiPage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapUiExecutionPolicyTest {

    private val policy = MapUiExecutionPolicy(
        maxActionCount = 12,
        maxRepeatedUnknownCount = 3
    )

    // 验证明确成功映射为成功结果。
    @Test
    fun mapsSuccessPageToSuccessOutcome() {
        assertEquals(
            MapUiExecutionOutcome.SUCCESS,
            policy.outcomeFor(
                page = MapUiPage.SUCCESS,
                actionCount = 2,
                repeatedUnknownCount = 0
            )
        )
    }

    // 验证阻断页面要求人工处理。
    @Test
    fun mapsBlockedPageToBlockedOutcome() {
        val outcome =
            policy.outcomeFor(
                page = MapUiPage.BLOCKED,
                actionCount = 1,
                repeatedUnknownCount = 0
            )

        assertEquals(
            MapUiExecutionOutcome.BLOCKED,
            outcome
        )
        assertEquals(
            MapOperationStatus.BLOCKED,
            outcome.toOperationStatus()
        )
    }

    // 验证未知页面重复三次后明确失败。
    @Test
    fun stopsAfterRepeatedUnknownPages() {
        assertEquals(
            MapUiExecutionOutcome.UNSUPPORTED,
            policy.outcomeFor(
                page = MapUiPage.UNKNOWN,
                actionCount = 2,
                repeatedUnknownCount = 3
            )
        )
    }

    // 验证动作次数达到上限后超时。
    @Test
    fun stopsAfterMaximumActions() {
        assertEquals(
            MapUiExecutionOutcome.TIMEOUT,
            policy.outcomeFor(
                page = MapUiPage.HOME,
                actionCount = 12,
                repeatedUnknownCount = 0
            )
        )
    }

    // 验证应用版本只接受完整精确匹配。
    @Test
    fun requiresExactSupportedAppVersion() {
        assertTrue(
            policy.isSupportedAppVersion(
                appVersion = "21.18.50",
                supportedAppVersion = "21.18.50"
            )
        )
        assertFalse(
            policy.isSupportedAppVersion(
                appVersion = "",
                supportedAppVersion = "21.18.50"
            )
        )
        assertFalse(
            policy.isSupportedAppVersion(
                appVersion = "21.18.50.1",
                supportedAppVersion = "21.18.50"
            )
        )
    }

    // 验证只有相同未知页面指纹才累计重复次数。
    @Test
    fun countsOnlyRepeatedUnknownFingerprints() {
        assertEquals(
            3,
            policy.repeatedUnknownCountFor(
                page = MapUiPage.UNKNOWN,
                fingerprint = "same",
                lastFingerprint = "same",
                repeatedUnknownCount = 2
            )
        )
        assertEquals(
            1,
            policy.repeatedUnknownCountFor(
                page = MapUiPage.UNKNOWN,
                fingerprint = "changed",
                lastFingerprint = "old",
                repeatedUnknownCount = 2
            )
        )
        assertEquals(
            0,
            policy.repeatedUnknownCountFor(
                page = MapUiPage.HOME,
                fingerprint = "same",
                lastFingerprint = "same",
                repeatedUnknownCount = 2
            )
        )
    }

    // 验证等待不计动作而成功点击增加一次动作。
    @Test
    fun countsOnlySuccessfulClickableActions() {
        assertEquals(
            4,
            policy.actionCountAfterSuccessfulAction(
                actionCount = 4,
                action = MapUiAction.WAIT
            )
        )
        assertEquals(
            5,
            policy.actionCountAfterSuccessfulAction(
                actionCount = 4,
                action = MapUiAction.CLICK_MINE
            )
        )
    }
}
