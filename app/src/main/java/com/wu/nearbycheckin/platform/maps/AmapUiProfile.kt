// @Time: 2026/07/26 04:05
// @Author: MrWu
// @File: AmapUiProfile.kt
// @Software: Android Studio


package com.wu.nearbycheckin.platform.maps

object AmapUiProfile : ProviderUiProfile {

    override val packageName = "com.autonavi.minimap"
    override val supportedAppVersion = "16.21.0.2010"
    override val profileVersion = "amap_16_21_0_2010_v1"

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
            snapshot.hasLabel("打卡成功") &&
                    snapshot.hasAnyLabel(listOf("完成", "成功")) ->
                decision(MapUiPage.SUCCESS, null, "打卡成功")
            snapshot.containsLabelFragment("当前地点已打卡") ->
                decision(
                    MapUiPage.ALREADY_COMPLETED,
                    null,
                    "当前地点已打卡"
                )
            snapshot.hasActionableLabel("开始打卡") ||
                    snapshot.hasActionableLabel("打卡") ->
                decision(
                    MapUiPage.CHECK_IN,
                    MapUiAction.CLICK_CHECK_IN,
                    "执行开始打卡"
                )
            snapshot.hasLabel("打卡点") ->
                decision(
                    MapUiPage.MINE,
                    MapUiAction.CLICK_CHECK_IN_POINT,
                    "进入打卡点"
                )
            snapshot.hasActionableLabel("我的") ->
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
}
