package com.wu.nearbycheckin.platform.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BaiduUiProfileTest {

    private val profile = BaiduUiProfile

    // 验证首页进入“我的”。
    @Test
    fun opensMineFromHome() {
        assertEquals(
            MapUiAction.CLICK_MINE,
            profile.decide(snapshot(clickable = listOf("我的"))).action
        )
    }

    // 验证百度允许由安全语义手势目标进入“我的”。
    @Test
    fun opensMineFromGestureTarget() {
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

        assertEquals(MapUiAction.CLICK_MINE, decision.action)
    }

    // 验证“我的”页面进入打卡点。
    @Test
    fun opensCheckInPointFromMine() {
        assertEquals(
            MapUiAction.CLICK_CHECK_IN_POINT,
            profile.decide(snapshot(clickable = listOf("打卡点"))).action
        )
    }

    // 验证历史页先安全返回首页。
    @Test
    fun returnsHomeFromHistoryPage() {
        val decision = profile.decide(
            snapshot(
                texts = listOf("我的足迹记录", "已打卡了1256个地点"),
                clickable = listOf("首页")
            )
        )

        assertEquals(MapUiPage.FOOTPRINT, decision.page)
        assertEquals(MapUiAction.CLICK_HOME, decision.action)
    }

    // 验证已知提示先关闭。
    @Test
    fun dismissesKnownFootprintPrompt() {
        assertEquals(
            MapUiAction.DISMISS_PROMPT,
            profile.decide(
                snapshot(
                    texts = listOf("您有1个足迹点待点亮"),
                    clickable = listOf("知道了")
                )
            ).action
        )
    }

    // 验证打卡页执行立即打卡。
    @Test
    fun performsImmediateCheckIn() {
        assertEquals(
            MapUiAction.CLICK_CHECK_IN,
            profile.decide(
                snapshot(clickable = listOf("立即打卡"))
            ).action
        )
    }

    // 验证明确定成功组合才进入成功终态。
    @Test
    fun recognizesExplicitSuccessEvidence() {
        val decision = profile.decide(
            snapshot(texts = listOf("打卡成功", "完成"))
        )

        assertEquals(MapUiPage.SUCCESS, decision.page)
        assertNull(decision.action)
    }

    // 验证当前打卡页的明确提示可识别为已经完成。
    @Test
    fun recognizesExplicitAlreadyCompletedEvidence() {
        assertEquals(
            MapUiPage.ALREADY_COMPLETED,
            profile.decide(
                snapshot(texts = listOf("当前地点今日已打卡"))
            ).page
        )
    }

    // 验证历史统计中的“已打卡”不会误判终态。
    @Test
    fun doesNotTreatHistoryStatisticsAsCompleted() {
        val decision = profile.decide(
            snapshot(
                texts = listOf("我的足迹记录", "2026年已打卡了256个地点"),
                clickable = listOf("首页")
            )
        )

        assertEquals(MapUiPage.FOOTPRINT, decision.page)
        assertEquals(MapUiAction.CLICK_HOME, decision.action)
    }

    // 验证登录或验证码页面进入人工阻断。
    @Test
    fun blocksSecurityPages() {
        assertEquals(
            MapUiPage.BLOCKED,
            profile.decide(
                snapshot(texts = listOf("请登录后继续", "验证码"))
            ).page
        )
    }

    // 构造不依赖 Android 的百度页面快照。
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
