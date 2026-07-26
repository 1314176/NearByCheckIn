// @Time: 2026/07/26 04:02
// @Author: MrWu
// @File: AmapUiProfileTest.kt
// @Software: Android Studio


package com.wu.nearbycheckin.platform.maps

import org.junit.Assert.assertEquals
import org.junit.Test

class AmapUiProfileTest {

    private val profile = AmapUiProfile

    // 验证首页进入“我的”。
    @Test
    fun opensMineFromHome() {
        assertEquals(
            MapUiAction.CLICK_MINE,
            profile.decide(snapshot(clickable = listOf("我的"))).action
        )
    }

    // 验证高德允许安全语义手势目标进入“我的”。
    @Test
    fun opensMineFromGestureTarget() {
        val decision = profile.decide(
            snapshot(gestureTargets = listOf("我的"))
        )

        assertEquals(MapUiPage.HOME, decision.page)
        assertEquals(MapUiAction.CLICK_MINE, decision.action)
    }

    // 验证高德从“我的”页面进入打卡点。
    @Test
    fun opensCheckInPointFromMine() {
        val decision = profile.decide(
            snapshot(gestureTargets = listOf("打卡点"))
        )

        assertEquals(MapUiPage.MINE, decision.page)
        assertEquals(MapUiAction.CLICK_CHECK_IN_POINT, decision.action)
    }

    // 验证“打卡点”文本优先于底部“我的”入口交给安全执行器校验。
    @Test
    fun prefersCheckInPointTextOverMineEntry() {
        val decision = profile.decide(
            snapshot(
                texts = listOf("打卡点"),
                gestureTargets = listOf("我的")
            )
        )

        assertEquals(MapUiPage.MINE, decision.page)
        assertEquals(
            MapUiAction.CLICK_CHECK_IN_POINT,
            decision.action
        )
    }

    // 验证高德打卡页执行“开始打卡”。
    @Test
    fun startsCheckInFromCheckInPointPage() {
        val decision = profile.decide(
            snapshot(gestureTargets = listOf("开始打卡"))
        )

        assertEquals(MapUiPage.CHECK_IN, decision.page)
        assertEquals(MapUiAction.CLICK_CHECK_IN, decision.action)
    }

    // 验证真机“打卡”按钮也进入开始打卡动作。
    @Test
    fun startsCheckInFromCurrentCheckInLabel() {
        val decision = profile.decide(
            snapshot(gestureTargets = listOf("打卡"))
        )

        assertEquals(MapUiPage.CHECK_IN, decision.page)
        assertEquals(MapUiAction.CLICK_CHECK_IN, decision.action)
    }

    // 验证高德频率限制文案进入显式页面状态但不自行点击。
    @Test
    fun recognizesRateLimitedEvidence() {
        val decision = profile.decide(
            snapshot(texts = listOf("打卡太频繁，请稍后再试"))
        )

        assertEquals(MapUiPage.RATE_LIMITED, decision.page)
        assertEquals(MapUiAction.WAIT, decision.action)
    }

    // 验证高德不再沿用旧足迹动作。
    @Test
    fun doesNotUseLegacyFootprintRoute() {
        val decision = profile.decide(
            snapshot(gestureTargets = listOf("我的足迹"))
        )

        assertEquals(MapUiPage.UNKNOWN, decision.page)
        assertEquals(MapUiAction.WAIT, decision.action)
    }

    // 验证明确成功证据进入成功终态。
    @Test
    fun recognizesExplicitSuccessEvidence() {
        assertEquals(
            MapUiPage.SUCCESS,
            profile.decide(
                snapshot(texts = listOf("打卡成功", "完成"))
            ).page
        )
    }

    // 验证当前地点明确提示已打卡时进入已完成终态。
    @Test
    fun recognizesExplicitAlreadyCompletedEvidence() {
        assertEquals(
            MapUiPage.ALREADY_COMPLETED,
            profile.decide(
                snapshot(texts = listOf("当前地点已打卡"))
            ).page
        )
    }

    // 验证账号安全页面进入人工阻断。
    @Test
    fun blocksSecurityPages() {
        assertEquals(
            MapUiPage.BLOCKED,
            profile.decide(
                snapshot(texts = listOf("登录", "安全验证"))
            ).page
        )
    }

    // 构造不依赖 Android 的高德页面快照。
    private fun snapshot(
        texts: List<String> = emptyList(),
        clickable: List<String> = emptyList(),
        gestureTargets: List<String> = emptyList()
    ): MapUiSnapshot {
        return MapUiSnapshot(
            packageName = profile.packageName,
            appVersion = profile.supportedAppVersion,
            nodes = texts.map { text ->
                MapUiNodeSnapshot(text = text)
            } + clickable.map { text ->
                MapUiNodeSnapshot(text = text, clickable = true)
            } + gestureTargets.map { text ->
                MapUiNodeSnapshot(text = text, gestureTarget = true)
            }
        )
    }
}
