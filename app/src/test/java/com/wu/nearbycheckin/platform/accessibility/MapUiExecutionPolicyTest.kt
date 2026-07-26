// @Time: 2026/07/26 04:40
// @Author: MrWu
// @File: MapUiExecutionPolicyTest.kt
// @Software: Android Studio


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

    // 验证未知页面首次出现和指纹变化时都从零重新计数。
    @Test
    fun resetsUnknownCountForFirstOrChangedFingerprint() {
        assertEquals(
            0,
            policy.repeatedUnknownCountFor(
                page = MapUiPage.UNKNOWN,
                fingerprint = "first",
                lastFingerprint = "",
                repeatedUnknownCount = 0
            )
        )
        assertEquals(
            0,
            policy.repeatedUnknownCountFor(
                page = MapUiPage.UNKNOWN,
                fingerprint = "changed",
                lastFingerprint = "old",
                repeatedUnknownCount = 2
            )
        )
    }

    // 验证相同未知页面连续重复三次后才达到停止阈值。
    @Test
    fun stopsOnlyAfterThreeRepeatedUnknownFingerprints() {
        val firstCount = policy.repeatedUnknownCountFor(
            page = MapUiPage.UNKNOWN,
            fingerprint = "same",
            lastFingerprint = "",
            repeatedUnknownCount = 0
        )
        val firstRepeatCount = policy.repeatedUnknownCountFor(
            page = MapUiPage.UNKNOWN,
            fingerprint = "same",
            lastFingerprint = "same",
            repeatedUnknownCount = firstCount
        )
        val secondRepeatCount = policy.repeatedUnknownCountFor(
            page = MapUiPage.UNKNOWN,
            fingerprint = "same",
            lastFingerprint = "same",
            repeatedUnknownCount = firstRepeatCount
        )
        val thirdRepeatCount = policy.repeatedUnknownCountFor(
            page = MapUiPage.UNKNOWN,
            fingerprint = "same",
            lastFingerprint = "same",
            repeatedUnknownCount = secondRepeatCount
        )

        assertEquals(0, firstCount)
        assertEquals(1, firstRepeatCount)
        assertEquals(2, secondRepeatCount)
        assertEquals(
            MapUiExecutionOutcome.CONTINUE,
            policy.outcomeFor(
                page = MapUiPage.UNKNOWN,
                actionCount = 0,
                repeatedUnknownCount = secondRepeatCount
            )
        )
        assertEquals(3, thirdRepeatCount)
        assertEquals(
            MapUiExecutionOutcome.UNSUPPORTED,
            policy.outcomeFor(
                page = MapUiPage.UNKNOWN,
                actionCount = 0,
                repeatedUnknownCount = thirdRepeatCount
            )
        )
    }

    // 验证非未知页面始终清零未知页面重复次数。
    @Test
    fun clearsUnknownCountForKnownPage() {
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

    // 验证陈旧频率限制页按未知页重复上限停止。
    @Test
    fun countsRateLimitedPageAsRepeatedUnknown() {
        assertEquals(
            3,
            policy.repeatedUnknownCountFor(
                page = MapUiPage.RATE_LIMITED,
                fingerprint = "rate-limited",
                lastFingerprint = "rate-limited",
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
