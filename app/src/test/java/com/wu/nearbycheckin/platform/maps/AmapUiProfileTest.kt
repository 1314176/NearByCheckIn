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

    // 验证高德 Profile 不把手势目标作为可点击动作。
    @Test
    fun doesNotUseGestureTargetForAmap() {
        val decision = profile.decide(
            MapUiSnapshot(
                packageName = profile.packageName,
                appVersion = profile.supportedAppVersion,
                nodes = listOf(
                    MapUiNodeSnapshot(
                        text = "我的",
                        gestureTarget = true
                    )
                )
            )
        )

        assertEquals(MapUiPage.UNKNOWN, decision.page)
        assertEquals(MapUiAction.WAIT, decision.action)
    }

    // 验证当前位置页直接点击打卡并跳过足迹入口。
    @Test
    fun checksInDirectlyWhenCurrentLocationPageIsVisible() {
        val decision = profile.decide(
            snapshot(
                texts = listOf("当前位置：", "浦联佳苑"),
                clickable = listOf("打卡")
            )
        )

        assertEquals(MapUiPage.CHECK_IN, decision.page)
        assertEquals(MapUiAction.CLICK_CHECK_IN, decision.action)
    }

    // 验证只有未出现打卡按钮时才进入足迹。
    @Test
    fun opensFootprintOnlyWhenCheckInIsUnavailable() {
        val decision = profile.decide(
            snapshot(clickable = listOf("我的足迹"))
        )

        assertEquals(MapUiPage.MINE, decision.page)
        assertEquals(MapUiAction.CLICK_FOOTPRINT, decision.action)
    }

    // 验证未知页面同时出现打卡和足迹时不会错误进入足迹。
    @Test
    fun waitsWhenCheckInAndFootprintAreBothClickableWithoutLocationEvidence() {
        val decision = profile.decide(
            snapshot(clickable = listOf("打卡", "我的足迹"))
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
        clickable: List<String> = emptyList()
    ): MapUiSnapshot {
        return MapUiSnapshot(
            packageName = profile.packageName,
            appVersion = profile.supportedAppVersion,
            nodes = texts.map { text ->
                MapUiNodeSnapshot(text = text)
            } + clickable.map { text ->
                MapUiNodeSnapshot(text = text, clickable = true)
            }
        )
    }
}
