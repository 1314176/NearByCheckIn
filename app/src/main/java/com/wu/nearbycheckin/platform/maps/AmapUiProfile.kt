// @Time: 2026/07/26 04:05
// @Author: MrWu
// @File: AmapUiProfile.kt
// @Software: Android Studio


package com.wu.nearbycheckin.platform.maps

object AmapUiProfile : ProviderUiProfile {

    override val packageName = "com.autonavi.minimap"
    override val profileVersion = "amap_semantic_v25"

    // 优先识别阻断和终态，再按“开始打卡、打卡点、我的”规划动作。
    override fun decide(snapshot: MapUiSnapshot): MapUiDecision {
        return when {
            snapshot.containsAnyLabelFragment(BLOCKED_MARKERS) ->
                decision(MapUiPage.BLOCKED, MapUiAction.BLOCK, "人工阻断")
            snapshot.containsLabelFragment("打卡太频繁") ->
                decision(
                    MapUiPage.RATE_LIMITED,
                    MapUiAction.WAIT,
                    "打卡太频繁"
                )
            snapshot.containsAnyLabelFragment(SEARCH_OVERLAY_MARKERS) ->
                decision(
                    MapUiPage.HOME,
                    MapUiAction.CLICK_HOME,
                    "关闭高德搜索覆盖页"
                )
            snapshot.hasLabel("打卡成功") &&
                    snapshot.hasAnyLabel(listOf("完成", "成功")) ->
                decision(MapUiPage.SUCCESS, null, "打卡成功")
            snapshot.containsLabelFragment("当前地点已打卡") ->
                decision(
                    MapUiPage.ALREADY_COMPLETED,
                    null,
                    "当前地点已打卡"
                )
            snapshot.hasLabel("添加回忆") &&
                    snapshot.hasActionableLabel("收下") ->
                decision(
                    MapUiPage.SUCCESS,
                    MapUiAction.CLICK_DONE,
                    "打卡成功，等待关闭添加回忆页"
                )
            snapshot.hasActionableLabel("开始打卡") ||
                    snapshot.hasActionableLabel("打卡") ->
                decision(
                    MapUiPage.CHECK_IN,
                    MapUiAction.CLICK_CHECK_IN,
                    "执行开始打卡"
                )
            snapshot.hasActionableLabel("足迹") ->
                decision(
                    MapUiPage.MINE,
                    MapUiAction.CLICK_FOOTPRINT,
                    "进入足迹"
                )
            snapshot.hasActionableLabel("地点") &&
                    snapshot.hasAnyLabel(FOOTPRINT_PAGE_MARKERS) ->
                decision(
                    MapUiPage.MINE,
                    MapUiAction.CLICK_CHECK_IN_POINT,
                    "进入足迹地点"
                )
            snapshot.hasLabel("打卡点") ->
                decision(
                    MapUiPage.MINE,
                    MapUiAction.CLICK_CHECK_IN_POINT,
                    "进入打卡点"
                )
            snapshot.hasActionableLabel("我的") &&
                    snapshot.containsAnyLabelFragment(HOME_MARKERS) ->
                decision(
                    MapUiPage.HOME,
                    MapUiAction.CLICK_MINE,
                    "进入我的"
                )
            else ->
                decision(MapUiPage.UNKNOWN, MapUiAction.WAIT, "未知页面")
        }
    }

    // 创建包含页面、动作和证据的高德决策。
    private fun decision(
        page: MapUiPage,
        action: MapUiAction?,
        evidence: String
    ): MapUiDecision {
        return MapUiDecision(
            page = page,
            action = action,
            evidence = evidence
        )
    }

    private val BLOCKED_MARKERS = listOf(
        "请登录后继续",
        "验证码",
        "安全验证",
        "风险验证",
        "需要授权"
    )

    private val FOOTPRINT_PAGE_MARKERS = listOf(
        "角落",
        "轨迹"
    )

    private val HOME_MARKERS = listOf(
        "maphome_searchbar",
        "home_scroll_view",
        "搜索框，输入文字"
    )

    private val SEARCH_OVERLAY_MARKERS = listOf(
        "请输入搜索内容",
        "地图选点"
    )
}
