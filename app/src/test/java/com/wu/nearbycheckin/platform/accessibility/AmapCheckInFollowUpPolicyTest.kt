// @Time: 2026/07/26 23:49
// @Author: MrWu
// @File: AmapCheckInFollowUpPolicyTest.kt
// @Software: Android Studio


package com.wu.nearbycheckin.platform.accessibility

import com.wu.nearbycheckin.core.model.LocationSnapshot
import com.wu.nearbycheckin.core.model.MapOperationEvidence
import com.wu.nearbycheckin.core.model.MapOperationStatus
import com.wu.nearbycheckin.core.model.MapProvider
import com.wu.nearbycheckin.core.model.MovementClass
import com.wu.nearbycheckin.core.model.ProviderPlaceCandidate
import com.wu.nearbycheckin.platform.maps.MapUiAction
import com.wu.nearbycheckin.platform.maps.MapUiPage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    // 验证第二次点击后的真实频率提示仍需等待最终成功证据。
    @Test
    fun keepsRateLimitUnknownAfterSecondProviderClick() {
        val evidence = evidence("高德等待两秒后执行第二次打卡")

        assertEquals(
            MapOperationStatus.UNKNOWN,
            AmapCheckInFollowUpPolicy.resolveProviderEvidence(
                evidence = evidence,
                secondClickSubmitted = false
            ).resultStatus
        )
        val waiting = AmapCheckInFollowUpPolicy.resolveProviderEvidence(
            evidence = evidence,
            secondClickSubmitted = true,
            transientMessage = "打卡太频繁"
        )
        assertEquals(MapOperationStatus.UNKNOWN, waiting.resultStatus)
        assertTrue(waiting.message.contains("地点总数未增加"))
    }

    // 验证内部等待文案不能伪装成高德真实频率提示。
    @Test
    fun ignoresSyntheticRateLimitMessage() {
        val evidence = evidence("高德等待两秒后执行第二次打卡")

        val waiting = AmapCheckInFollowUpPolicy.resolveProviderEvidence(
            evidence = evidence,
            secondClickSubmitted = true
        )

        assertEquals(evidence, waiting)
    }

    // 验证真实地址列表会触发入口占位任务清理。
    @Test
    fun distinguishesEntryPlaceholderFromRealCandidate() {
        val placeholder = candidate(
            name = "当前高德打卡位置",
            stableHint = "device-real-location"
        )
        val historicalPrompt = candidate(
            name = "当前位置：",
            stableHint = "当前位置：|江南门窗厂"
        )
        val realCandidate = candidate(
            name = "IN健身工作坊",
            stableHint = "IN健身工作坊|沪太路1353号"
        )

        assertTrue(
            AmapCandidateTransitionPolicy.isEntryPlaceholder(placeholder)
        )
        assertTrue(
            AmapCandidateTransitionPolicy.isEntryPlaceholder(historicalPrompt)
        )
        assertFalse(
            AmapCandidateTransitionPolicy.isEntryPlaceholder(realCandidate)
        )
        assertTrue(
            AmapCandidateTransitionPolicy.hasRealCandidate(
                listOf(placeholder, realCandidate)
            )
        )
    }

    // 验证高德真实位置键能区分相邻位置并继续复用同一舍入位置。
    @Test
    fun bindsEntryCandidateToPreciseRealLocation() {
        val placeholder = candidate(
            name = "当前高德打卡位置",
            stableHint = "device-real-location"
        )
        val first = AmapCandidateTransitionPolicy.bindRealLocation(
            candidate = placeholder,
            location = location(31.268300, 121.439810)
        )
        val sameRoundedLocation =
            AmapCandidateTransitionPolicy.bindRealLocation(
                candidate = placeholder,
                location = location(31.268301, 121.439811)
            )
        val nextLocation = AmapCandidateTransitionPolicy.bindRealLocation(
            candidate = placeholder,
            location = location(31.268315, 121.439825)
        )

        assertEquals(first.toPlaceKey(), sameRoundedLocation.toPlaceKey())
        assertNotEquals(first.toPlaceKey(), nextLocation.toPlaceKey())
        assertTrue(first.stableHint.startsWith("device-real-location:"))
    }

    // 创建高德未知结果证据。
    private fun evidence(message: String): MapOperationEvidence {
        return MapOperationEvidence(
            provider = MapProvider.AMAP,
            taskId = "task",
            commandId = "command",
            profileVersion = "amap-test",
            page = "BLOCKED",
            matchedText = "当前位置",
            viewId = "start",
            beforeFingerprint = "before",
            afterFingerprint = "after",
            resultStatus = MapOperationStatus.UNKNOWN,
            message = message,
            observedAtMillis = 100L
        )
    }

    // 创建高德候选用于验证入口与真实地点转换策略。
    private fun candidate(
        name: String,
        stableHint: String
    ): ProviderPlaceCandidate {
        return ProviderPlaceCandidate(
            provider = MapProvider.AMAP,
            name = name,
            address = "测试地址",
            distanceText = "100米",
            completed = false,
            fingerprint = name,
            observedAtMillis = 100L,
            stableHint = stableHint
        )
    }

    // 创建用于真实位置键测试的有效定位快照。
    private fun location(
        latitude: Double,
        longitude: Double
    ): LocationSnapshot {
        return LocationSnapshot(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = 3F,
            speedKmh = 5F,
            timestampMillis = 1_000L,
            movementClass = MovementClass.MOVING
        )
    }
}
