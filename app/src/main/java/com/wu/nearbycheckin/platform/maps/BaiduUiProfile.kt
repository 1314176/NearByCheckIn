// @Time: 2026/07/26 03:46
// @Author: MrWu
// @File: BaiduUiProfile.kt
// @Software: Android Studio


package com.wu.nearbycheckin.platform.maps

object BaiduUiProfile : ProviderUiProfile {

    override val packageName = "com.baidu.BaiduMap"
    override val profileVersion = "baidu_semantic_v2"

    // 按阻断、成功、弹窗、历史页、打卡页和入口页的顺序规划动作。
    override fun decide(snapshot: MapUiSnapshot): MapUiDecision {
        return when {
            snapshot.containsAnyLabelFragment(BLOCKED_MARKERS) ->
                decision(MapUiPage.BLOCKED, MapUiAction.BLOCK, "人工阻断")
            snapshot.hasLabel("打卡成功") &&
                    snapshot.hasAnyLabel(listOf("完成", "成功")) ->
                decision(
                    MapUiPage.SUCCESS,
                    MapUiAction.CLICK_DONE,
                    "打卡成功，等待关闭结果页"
                )
            snapshot.containsLabelFragment(SHARE_PANEL_MARKER) ->
                decision(
                    MapUiPage.SHARE_PANEL,
                    MapUiAction.DISMISS_SHARE_PANEL,
                    "关闭打卡分享面板"
                )
            snapshot.containsLabelFragment("当前地点今日已打卡") ->
                decision(
                    MapUiPage.ALREADY_COMPLETED,
                    null,
                    "当前地点今日已打卡"
                )
            snapshot.hasActionableLabel("知道了") &&
                    snapshot.containsAnyLabelFragment(KNOWN_PROMPT_MARKERS) ->
                decision(
                    MapUiPage.HOME,
                    MapUiAction.DISMISS_PROMPT,
                    "关闭足迹提示"
                )
            snapshot.hasLabel("我的足迹记录") &&
                    snapshot.hasActionableLabel("首页") ->
                decision(
                    MapUiPage.FOOTPRINT,
                    MapUiAction.CLICK_HOME,
                    "历史页返回首页"
                )
            snapshot.hasActionableLabel("立即打卡") ||
                    snapshot.hasActionableLabel("立刻打卡") ->
                decision(
                    MapUiPage.CHECK_IN,
                    MapUiAction.CLICK_CHECK_IN,
                    "执行立即打卡"
                )
            snapshot.hasActionableLabel("打卡点") ->
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

    // 创建包含页面、动作和证据的百度决策。
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
    private val KNOWN_PROMPT_MARKERS = listOf(
        "足迹点待点亮"
    )
    private const val SHARE_PANEL_MARKER = "请选择分享主题"
}
